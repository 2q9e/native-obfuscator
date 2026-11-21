package dev.nativeobfuscator;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.Console;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class Main {
    private static final int MAX_CONFIG_BYTES = 1024 * 1024;

    private Main() {}

    public static void main(String[] arguments) {
        System.exit(run(arguments));
    }

    private static int run(String[] arguments) {
        if (arguments.length == 0 || (arguments.length == 1 && arguments[0].equals("--help"))) {
            System.out.print("""
                Usage:
                  native-obfuscator analyze --input IN.jar --include 'pkg/Class#method*'
                  native-obfuscator protect --input IN.jar --output OUT.jar --include 'pkg/Class#method*'
                  native-obfuscator harden --input IN.jar --output STAGED.jar --include 'pkg/Class#method*'
                  native-obfuscator verify --input IN.jar --staged STAGED.jar --include 'pkg/Class#method*'
                  native-obfuscator apply --input IN.jar --staged STAGED.jar --output RELEASE.jar --include 'pkg/Class#method*' --confirm-overwrite
                  native-obfuscator restore --output RELEASE.jar

                --include GLOB        Select an owner#name(descriptor) signature. Repeatable.
                --exclude GLOB        Retain a matching signature in Java. Repeatable.
                --min-instructions N  Skip compatible methods under N JVM instructions (default 0).
                --report-native-sizes Report exact Linux function sizes as JSON records.
                --dry-run            With harden, report eligible methods without writing output.
                --state-dir PATH     Store apply/restore state (default ~/.native-obfuscator/state).
                --unsupported POLICY  fail (default) or retain.
                --target TARGET       Repeatable. x86_64-linux, aarch64-linux,
                                      x86_64-windows, aarch64-windows, or all.
                --zig PATH            Zig executable for cross-compilation. Host Linux can use CC.
                --config PATH         Read options from a JSON file instead of command-line options.
                --profile PROFILE     compatibility, balanced, aggressive or maximum.
                --seed UNSIGNED        Reproducible LLVM build seed (default 0).
                --max-growth PERCENT  Cap executable code growth relative to the LLVM baseline.
                --analysis-resistance MODE  off (default), balanced, or maximum.
                --analysis-seed UINT64      Independent deterministic seed (default 0).
                --analysis-resource-budget BYTES  Cap additional uncompressed analysis resources.
                --analysis-entry-budget COUNT    Cap generated analysis resource entries.
                --analysis-helper-methods COUNT  Cap application methods with native helpers.
                --analysis-helpers-per-method COUNT  Cap linked helper depth per eligible method.
                --analysis-decoy-methods COUNT   Cap independently generated native utility methods.
                --analysis-llvm-sites COUNT      Cap extra preset LLVM sites per function.
                --padding MODE        on (12 classes) or off. Interactive JAR builds prompt.
                --padding-count N     Generate 0-1000 utility classes; positive enables padding.
                --padding-seed INT64  Reproduce generated classes independently of --seed (default 0).
                --markers COUNT       Add 1-1000 inert analysis markers (disabled by default).
                --marker-seed UINT64  Reproducible marker seed (default 0).
                --emit-c              Write generated C to --output for inspection.
                --clang PATH          Clang executable for optional LLVM processing.
                --llvm-opt PATH       Matching opt executable.
                --llvm-plugin PATH    Trusted local LLVM pass plugin.
                --llvm-passes TEXT    Explicit opt pipeline.
                """);
            return arguments.length == 0 ? 2 : 0;
        }
        try {
            var gson = new Gson();
            if (arguments[0].equals("harden")) return runHarden(arguments, gson);
            if (arguments[0].equals("verify")) return runVerify(arguments, gson);
            if (arguments[0].equals("apply")) return runApply(arguments, gson);
            if (arguments[0].equals("restore")) return runRestore(arguments, gson);
            var config = parse(arguments);
            int count = runProtection(config, gson, decision -> {});
            System.err.println((config.analyze() ? "Eligible" : "Generated") + " methods: " + count);
            return 0;
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return 2;
        } catch (IOException e) {
            System.err.println(e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Native compilation interrupted");
            return 1;
        }
    }

    private record Coverage(Protector.Config config, Set<String> methods) {}

    private static int runHarden(String[] arguments, Gson gson) throws IOException, InterruptedException {
        arguments = expandConfig(arguments);
        Set<String> flags = actionFlags(arguments);
        if (flags.contains("--emit-c"))
            throw new IllegalArgumentException("harden requires a staged JAR output and cannot use --emit-c");
        if (flags.contains("--confirm-overwrite"))
            throw new IllegalArgumentException("--confirm-overwrite requires apply");
        boolean dryRun = flags.contains("--dry-run");
        String[] protectArguments = arguments.clone();
        protectArguments[0] = "protect";
        var config = parse(protectArguments, dryRun);
        if (dryRun) {
            int count = runProtection(config, gson, decision -> {});
            System.err.println("Eligible methods: " + count);
            System.out.println(gson.toJson(Map.of("status", "dry-run-complete",
                "eligibleMethods", count, "writesOutput", false,
                "nextSteps", "Run harden without --dry-run to create and verify a staged JAR.")));
            return 0;
        }
        Path candidate = Files.createTempFile(config.output().getParent(), ".native-obfuscator-stage-", ".jar");
        Workflow.VerificationReport report;
        int count;
        try {
            var expected = new java.util.TreeSet<String>();
            count = runProtection(config.withOutput(candidate), gson, decision -> {
                if (decision.result().equals("selected")) expected.add(decision.method());
            });
            report = Workflow.verify(config.input(), candidate, expected, targetKeys(config.targets()),
                config.paddingCount(), config.paddingSeed(), config.markers(), config.markerSeed(), config.analysis(),
                config.clang());
            if (report.structurallyValid()) Workflow.publishStage(candidate, config.output(), report);
        } finally {
            Files.deleteIfExists(candidate);
        }
        System.out.println(gson.toJson(Map.of("status", report.structurallyValid() ? "staged" : "verification-failed",
            "generatedMethods", count, "verification", report,
            "nextSteps", report.structurallyValid()
                ? "Run verify, then apply the staged JAR to a release path."
                : "Inspect the verification errors and keep the source JAR unchanged.")));
        System.err.println((report.structurallyValid() ? "Staged and verified" : "Structural verification failed")
            + " methods: " + count);
        return report.structurallyValid() ? 0 : 1;
    }

    private static int runVerify(String[] arguments, Gson gson) throws IOException, InterruptedException {
        arguments = expandConfig(arguments);
        Map<String, String> options = workflowOptions(arguments, true, false, false, false);
        String staged = required(options, "--staged");
        Coverage coverage = analyzeCoverage(arguments, gson);
        var report = verifyCoverage(coverage, Path.of(staged), options.get("--clang"));
        System.out.println(gson.toJson(Map.of("status", report.structurallyValid() ? "verified" : "verification-failed",
            "verification", report,
            "nextSteps", report.structurallyValid()
                ? "The JAR is structurally valid. Runtime equivalence still requires running your own application checks."
                : "Fix the reported structural issues before applying this JAR.")));
        return report.structurallyValid() ? 0 : 1;
    }

    private static int runApply(String[] arguments, Gson gson) throws IOException, InterruptedException {
        arguments = expandConfig(arguments);
        Map<String, String> options = workflowOptions(arguments, true, true, true, true);
        String staged = required(options, "--staged");
        String release = required(options, "--output");
        Coverage coverage = analyzeCoverage(arguments, gson);
        var verification = verifyCoverage(coverage, Path.of(staged), options.get("--clang"));
        if (!verification.structurallyValid()) {
            System.out.println(gson.toJson(Map.of("status", "verification-failed", "verification", verification,
                "nextSteps", "Fix the reported structural issues before applying this JAR.")));
            return 1;
        }
        Path stateRoot = stateRoot(options);
        var result = Workflow.apply(Path.of(staged), Path.of(release), stateRoot,
            options.containsKey("--confirm-overwrite"), verification.stagedSha256());
        System.out.println(gson.toJson(Map.of("status", result.status(), "promotion", result,
            "verification", verification,
            "nextSteps", result.status().equals("confirmation-required")
                ? "Review the release path and rerun with --confirm-overwrite."
                : "Use restore to roll back this promotion if needed.")));
        return result.status().equals("confirmation-required") ? 2 : 0;
    }

    private static int runRestore(String[] arguments, Gson gson) throws IOException {
        arguments = expandConfig(arguments);
        Map<String, String> options = workflowOptions(arguments, false, true, true, false);
        String release = required(options, "--output");
        var result = Workflow.restore(Path.of(release), stateRoot(options));
        System.out.println(gson.toJson(Map.of("status", result.status(), "promotion", result)));
        return 0;
    }

    private static String[] expandConfig(String[] arguments) throws IOException {
        return arguments.length == 3 && arguments[1].equals("--config")
            ? loadConfig(arguments[0], Path.of(arguments[2])) : arguments;
    }

    private static Workflow.VerificationReport verifyCoverage(Coverage coverage, Path staged, String clang)
            throws IOException, InterruptedException {
        var config = coverage.config;
        return Workflow.verify(config.input(), staged, coverage.methods, verificationTargets(config),
            config.paddingCount(), config.paddingSeed(), config.markers(), config.markerSeed(), config.analysis(), clang);
    }

    private static int runProtection(Protector.Config config, Gson gson, Consumer<Protector.Decision> collect)
            throws IOException, InterruptedException {
        return Protector.run(config, decision -> {
            collect.accept(decision);
            System.out.println(gson.toJson(decision));
        }, analysis -> System.out.println(gson.toJson(analysis)));
    }

    private static Coverage analyzeCoverage(String[] arguments, Gson gson)
            throws IOException, InterruptedException {
        Protector.Config config = parse(coverageArguments(arguments));
        var eligible = new java.util.TreeSet<String>();
        var decisions = new ArrayList<Protector.Decision>();
        runProtection(config, gson, decisions::add);
        decisions.stream().filter(decision -> decision.result().equals("eligible"))
            .map(Protector.Decision::method).forEach(eligible::add);
        return new Coverage(config, Set.copyOf(eligible));
    }

    private static String[] coverageArguments(String[] arguments) throws IOException {
        String[] source = arguments;
        if (arguments.length == 3 && arguments[1].equals("--config"))
            source = loadConfig("analyze", Path.of(arguments[2]));
        var filtered = new ArrayList<String>();
        filtered.add("analyze");
        Set<String> retained = coverageOptions();
        Set<String> ignoredValues = Set.of("--output", "--staged", "--state-dir", "--zig",
            "--clang", "--llvm-opt", "--llvm-plugin", "--llvm-passes", "--profile", "--seed",
            "--max-growth");
        Set<String> ignoredFlags = Set.of("--dry-run", "--confirm-overwrite", "--emit-c",
            "--report-native-sizes");
        for (int i = 1; i < source.length; i++) {
            String key = source[i];
            if (retained.contains(key)) {
                if (++i == source.length) throw new IllegalArgumentException("Missing value for " + key);
                filtered.add(key);
                filtered.add(source[i]);
            } else if (ignoredValues.contains(key)) {
                if (++i == source.length) throw new IllegalArgumentException("Missing value for " + key);
            } else if (ignoredFlags.contains(key)) {
                continue;
            } else {
                throw new IllegalArgumentException("Unknown option " + key);
            }
        }
        return filtered.toArray(String[]::new);
    }

    private static Set<String> coverageOptions() {
        return Set.of("--input", "--include", "--exclude", "--target", "--unsupported",
            "--min-instructions", "--padding", "--padding-count", "--padding-seed", "--markers",
            "--marker-seed", "--analysis-resistance", "--analysis-seed", "--analysis-resource-budget",
            "--analysis-entry-budget", "--analysis-helper-methods", "--analysis-helpers-per-method",
            "--analysis-decoy-methods", "--analysis-llvm-sites");
    }

    private static Map<String, String> workflowOptions(String[] arguments, boolean allowStaged,
            boolean allowOutput, boolean allowStateDir, boolean allowConfirm) {
        var options = new HashMap<String, String>();
        Set<String> coverageValues = coverageOptions();
        Set<String> ignoredValues = Set.of("--zig", "--llvm-opt", "--llvm-plugin",
            "--llvm-passes", "--profile", "--seed", "--max-growth");
        for (int i = 1; i < arguments.length; i++) {
            String key = arguments[i];
            if (coverageValues.contains(key) || ignoredValues.contains(key)) {
                if (++i == arguments.length) throw new IllegalArgumentException("Missing value for " + key);
                continue;
            }
            if (key.equals("--staged") && allowStaged
                || key.equals("--output") && allowOutput
                || key.equals("--state-dir") && allowStateDir
                || key.equals("--clang") && allowStaged) {
                if (++i == arguments.length) throw new IllegalArgumentException("Missing value for " + key);
                if (options.putIfAbsent(key, arguments[i]) != null)
                    throw new IllegalArgumentException("Repeated option " + key);
                continue;
            }
            if (key.equals("--confirm-overwrite") && allowConfirm) {
                if (options.putIfAbsent(key, "true") != null)
                    throw new IllegalArgumentException("Repeated option " + key);
                continue;
            }
            if (Set.of("--dry-run", "--emit-c", "--report-native-sizes").contains(key))
                continue;
            throw new IllegalArgumentException("Unknown option " + key);
        }
        return options;
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null) throw new IllegalArgumentException("Required option " + key);
        return value;
    }

    private static Path stateRoot(Map<String, String> options) {
        return options.containsKey("--state-dir") ? Path.of(options.get("--state-dir"))
            : Path.of(System.getProperty("user.home"), ".native-obfuscator", "state");
    }

    private static Set<String> targetKeys(List<NativeTarget> targets) {
        var keys = new java.util.TreeSet<String>();
        targets.forEach(target -> keys.add(target.key));
        return Set.copyOf(keys);
    }

    private static Set<String> verificationTargets(Protector.Config config) {
        Set<String> targets = targetKeys(config.targets());
        return targets.isEmpty() ? Set.of(NativeTarget.host().key) : targets;
    }

    private static Set<String> actionFlags(String[] arguments) {
        var flags = new LinkedHashSet<String>();
        for (int i = 1; i < arguments.length; i++) {
            String option = arguments[i];
            if (Set.of("--dry-run", "--emit-c", "--confirm-overwrite", "--report-native-sizes").contains(option)) {
                if (!flags.add(option)) throw new IllegalArgumentException("Repeated option " + option);
            } else {
                i++;
            }
        }
        return flags;
    }

    private static Protector.Config parse(String[] arguments) throws IOException {
        return parse(arguments, false);
    }

    private static Protector.Config parse(String[] arguments, boolean dryRun) throws IOException {
        if (arguments.length == 3 && arguments[1].equals("--config")) {
            arguments = loadConfig(arguments[0], Path.of(arguments[2]));
        }
        boolean analyze = switch (arguments[0]) {
            case "analyze" -> true;
            case "protect" -> false;
            default -> throw new IllegalArgumentException("Expected analyze or protect");
        };
        var includes = new ArrayList<Pattern>();
        var excludes = new ArrayList<Pattern>();
        var targets = new LinkedHashSet<NativeTarget>();
        Map<String, String> options = new HashMap<>();
        boolean emitC = false;
        boolean reportNativeSizes = false;
        for (int i = 1; i < arguments.length; i++) {
            String key = arguments[i];
            if (dryRun && key.equals("--dry-run")) continue;
            if (key.equals("--emit-c")) {
                if (emitC) throw new IllegalArgumentException("Repeated option " + key);
                emitC = true;
                continue;
            }
            if (key.equals("--report-native-sizes")) {
                if (reportNativeSizes) throw new IllegalArgumentException("Repeated option " + key);
                reportNativeSizes = true;
                continue;
            }
            if (++i == arguments.length) throw new IllegalArgumentException("Missing value for " + key);
            String value = arguments[i];
            switch (key) {
                case "--include" -> includes.add(glob(value));
                case "--exclude" -> excludes.add(glob(value));
                case "--target" -> {
                    if (value.equals("all")) targets.addAll(List.of(NativeTarget.values()));
                    else targets.add(NativeTarget.parse(value));
                }
                case "--input", "--output", "--zig", "--unsupported", "--clang", "--llvm-opt", "--llvm-plugin",
                     "--llvm-passes", "--min-instructions", "--profile", "--seed", "--max-growth", "--padding",
                     "--padding-count", "--padding-seed", "--markers", "--marker-seed", "--analysis-resistance",
                     "--analysis-seed", "--analysis-resource-budget", "--analysis-entry-budget",
                     "--analysis-helper-methods", "--analysis-helpers-per-method", "--analysis-decoy-methods",
                     "--analysis-llvm-sites" -> {
                    if (options.putIfAbsent(key, value) != null)
                        throw new IllegalArgumentException("Repeated option " + key);
                }
                default -> throw new IllegalArgumentException("Unknown option " + key);
            }
        }
        if (includes.isEmpty()) throw new IllegalArgumentException("Select at least one --include pattern");
        Path input = path(options, "--input");
        Path output = analyze || dryRun && !options.containsKey("--output") ? null : path(options, "--output");
        if (analyze && (options.containsKey("--output") || emitC))
            throw new IllegalArgumentException("Output options require protect");
        if (output != null && (input.equals(output) || (Files.exists(output) && Files.isSameFile(input, output))))
            throw new IllegalArgumentException("Input and output must be distinct files");
        boolean skip = switch (options.getOrDefault("--unsupported", "fail")) {
            case "fail" -> false;
            case "retain" -> true;
            default -> throw new IllegalArgumentException("Expected --unsupported fail or retain");
        };
        AnalysisConfig analysis = AnalysisConfig.resolve(options.get("--analysis-resistance"),
            options.get("--analysis-seed"), options.get("--analysis-resource-budget"), options.get("--analysis-entry-budget"),
            options.get("--analysis-helper-methods"), options.get("--analysis-helpers-per-method"),
            options.get("--analysis-decoy-methods"), options.get("--analysis-llvm-sites"));
        boolean llvm = options.containsKey("--clang") || options.containsKey("--llvm-opt")
            || options.containsKey("--llvm-plugin") || options.containsKey("--llvm-passes");
        String profile = options.getOrDefault("--profile", llvm ? "balanced" : "compatibility");
        int sites = switch (profile) {
            case "compatibility" -> 0;
            case "balanced" -> 4;
            case "aggressive" -> 12;
            case "maximum" -> 32;
            default -> throw new IllegalArgumentException("Unknown profile " + profile);
        };
        if (llvm && (analyze || emitC))
            throw new IllegalArgumentException("LLVM options require a protect build");
        if ((sites > 0 || llvm) && (!options.containsKey("--clang") || !options.containsKey("--llvm-opt")
            || !options.containsKey("--llvm-plugin")))
            throw new IllegalArgumentException("Specify --clang, --llvm-opt and --llvm-plugin together");
        if (llvm && sites == 0 && options.containsKey("--llvm-passes"))
            throw new IllegalArgumentException("Use a protection profile for an explicit LLVM pipeline");
        String seed = Long.toUnsignedString(Long.parseUnsignedLong(options.getOrDefault("--seed", "0")));
        int growth = options.containsKey("--max-growth") ? Integer.parseInt(options.get("--max-growth")) : -1;
        if (options.containsKey("--max-growth") && (growth < 0 || !llvm || sites == 0))
            throw new IllegalArgumentException("--max-growth needs a non-negative percentage and an LLVM protection profile");
        if (options.containsKey("--seed") && (!llvm || sites == 0 || options.containsKey("--llvm-passes")))
            throw new IllegalArgumentException("--seed applies to preset LLVM passes; configure explicit passes directly");
        String arithmetic = "native-encode<seed=" + seed + ";max=" + sites + ";prefix=method_>";
        int branches = switch (profile) {
            case "aggressive" -> 1;
            case "maximum" -> 3;
            default -> 0;
        };
        String preset = branches == 0 ? arithmetic
            : "native-diversify<seed=" + seed + ";max=" + branches + ";prefix=method_>," + arithmetic;
        boolean customPasses = options.containsKey("--llvm-passes");
        if (!analyze && options.containsKey("--analysis-llvm-sites") && analysis.llvmSites() > 0
            && (!llvm || sites == 0 || customPasses))
            throw new IllegalArgumentException("--analysis-llvm-sites needs the preset LLVM pipeline and its toolchain");
        if (llvm && sites > 0 && !customPasses && analysis.llvmSites() > 0)
            preset = extendedAnalysisPasses(preset, analysis);
        String passes = options.getOrDefault("--llvm-passes", preset);
        if (targets.isEmpty() && !analyze && !emitC) targets.add(NativeTarget.host());
        Path plugin = llvm && sites > 0 ? path(options, "--llvm-plugin") : null;
        int markers = Integer.parseInt(options.getOrDefault("--markers", "0"));
        long markerSeed = Long.parseUnsignedLong(options.getOrDefault("--marker-seed", "0"));
        if (markers < 0 || markers > 1000)
            throw new IllegalArgumentException("--markers must be between 0 and 1000");
        if ((markers > 0 && emitC) || (markers == 0 && options.containsKey("--marker-seed")))
            throw new IllegalArgumentException("Markers require JAR output or analyze and a positive --markers count");
        int minInstructions = Integer.parseInt(options.getOrDefault("--min-instructions", "0"));
        if (minInstructions < 0) throw new IllegalArgumentException("--min-instructions must be non-negative");
        if (reportNativeSizes && (analyze || emitC || targets.stream().noneMatch(t -> t.key.endsWith("-linux"))))
            throw new IllegalArgumentException("--report-native-sizes requires at least one Linux JAR target");
        int paddingCount = parsePadding(options, !emitC, !analyze && !dryRun);
        long paddingSeed;
        try { paddingSeed = Long.parseLong(options.getOrDefault("--padding-seed", "0")); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("--padding-seed must be a signed 64-bit integer"); }
        List<NativeTarget> orderedTargets = analysis.enabled() ? targets.stream().sorted().toList() : List.copyOf(targets);
        return new Protector.Config(input, output, List.copyOf(includes), List.copyOf(excludes), orderedTargets,
            options.get("--zig"), skip, analyze || dryRun, emitC, options.get("--clang"), options.get("--llvm-opt"),
            plugin, plugin == null ? null : passes, growth, markers, markerSeed, paddingCount, paddingSeed,
            minInstructions, reportNativeSizes, analysis);
    }

    private static String extendedAnalysisPasses(String legacy, AnalysisConfig analysis) {
        String seed = Long.toUnsignedString(analysis.seed());
        boolean route = analysis.profile() == AnalysisConfig.Profile.MAXIMUM;
        int mixSites = analysis.llvmSites() - (route ? 1 : 0);
        StringBuilder pipeline = new StringBuilder();
        if (route) pipeline.append("native-route<seed=").append(seed).append(";max=1;prefix=method_>,");
        if (mixSites > 0)
            pipeline.append("native-mix<seed=").append(seed).append(";max=").append(mixSites).append(";prefix=method_>,");
        return pipeline.append(legacy).toString();
    }

    private static int parsePadding(Map<String, String> options, boolean supported, boolean prompt) {
        String mode = options.get("--padding");
        String countText = options.get("--padding-count");
        if (!supported && (mode != null || countText != null || options.containsKey("--padding-seed")))
            throw new IllegalArgumentException("Padding requires JAR output or analyze");
        if (mode != null && !mode.equals("on") && !mode.equals("off"))
            throw new IllegalArgumentException("--padding must be on or off");
        Integer count = null;
        if (countText != null) {
            try { count = Integer.valueOf(countText); }
            catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("--padding-count must be an integer between 0 and 1000");
            }
            if (count < 0 || count > 1000)
                throw new IllegalArgumentException("--padding-count must be between 0 and 1000");
            if (mode != null && (mode.equals("on") != (count > 0)))
                throw new IllegalArgumentException("Conflicting --padding " + mode + " and --padding-count " + count);
        }
        if (count != null) return count;
        if (mode != null) return mode.equals("on") ? 12 : 0;
        Console console = System.console();
        if (!prompt || System.getenv("CI") != null || !isTerminal(console)) return 0;
        System.err.print("Add 12 utility classes as file padding? [y/N] ");
        System.err.flush();
        String answer = console.readLine();
        return answer != null && (answer.strip().equalsIgnoreCase("y") || answer.strip().equalsIgnoreCase("yes")) ? 12 : 0;
    }

    private static boolean isTerminal(Console console) {
        if (console == null) return false;
        try {
            // Newer JDKs can return a Console for redirected input as well.
            return Boolean.TRUE.equals(Console.class.getMethod("isTerminal").invoke(console));
        } catch (NoSuchMethodException java21) {
            return true;
        } catch (ReflectiveOperationException failure) {
            return false;
        }
    }

    private static String[] loadConfig(String action, Path path) throws IOException {
        var args = new ArrayList<>(List.of(action));
        try {
            byte[] bytes;
            try (var input = Files.newInputStream(path)) {
                bytes = input.readNBytes(MAX_CONFIG_BYTES + 1);
            }
            if (bytes.length > MAX_CONFIG_BYTES)
                throw new IOException("Config file exceeds size limit");
            String content = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
            var fields = readConfigFields(content);
            for (var field : fields.entrySet()) {
                String key = field.getKey();
                JsonElement value = field.getValue();
                String flag = "--" + switch (key) {
                    case "paddingCount" -> "padding-count";
                    case "paddingSeed" -> "padding-seed";
                    case "analysisResistance" -> "analysis-resistance";
                    case "analysisSeed" -> "analysis-seed";
                    case "analysisResourceBudget" -> "analysis-resource-budget";
                    case "analysisEntryBudget" -> "analysis-entry-budget";
                    case "analysisHelperMethods" -> "analysis-helper-methods";
                    case "analysisHelpersPerMethod" -> "analysis-helpers-per-method";
                    case "analysisDecoyMethods" -> "analysis-decoy-methods";
                    case "analysisLlvmSites" -> "analysis-llvm-sites";
                    default -> key;
                };
                if (key.equals("include") || key.equals("exclude") || key.equals("target")) {
                    for (JsonElement item : value.getAsJsonArray()) {
                        args.add(flag);
                        args.add(stringValue(item));
                    }
                } else if (Set.of("emit-c", "report-native-sizes", "dry-run", "confirm-overwrite").contains(key)) {
                    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
                        throw new IllegalArgumentException(key + " must be a boolean");
                    if (value.getAsBoolean()) args.add(flag);
                } else {
                    args.add(flag);
                    args.add(stringValue(value));
                }
            }
        } catch (RuntimeException failure) {
            throw new IOException("Invalid config " + path + ": " + failure.getMessage(), failure);
        }
        return args.toArray(String[]::new);
    }

    private static Map<String, JsonElement> readConfigFields(String content) throws IOException {
        var fields = new LinkedHashMap<String, JsonElement>();
        try (var reader = new JsonReader(new StringReader(content))) {
            reader.setStrictness(Strictness.STRICT);
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (fields.containsKey(name)) throw new IllegalArgumentException("Duplicate config field " + name);
                fields.put(name, JsonParser.parseReader(reader));
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT)
                throw new IllegalArgumentException("Trailing config data");
        }
        return fields;
    }

    private static String stringValue(JsonElement value) {
        if (!value.isJsonPrimitive() || value.getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException("Expected a string or a number");
        return value.getAsString();
    }

    private static Path path(Map<String, String> options, String key) {
        if (!options.containsKey(key)) throw new IllegalArgumentException("Required option " + key);
        return Path.of(options.get(key)).toAbsolutePath().normalize();
    }

    private static Pattern glob(String input) {
        var regex = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '*') regex.append(".*");
            else if (c == '?') regex.append('.');
            else regex.append(Pattern.quote(String.valueOf(c)));
        }
        return Pattern.compile(regex.toString());
    }
}
