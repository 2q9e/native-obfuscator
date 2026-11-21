package dev.nativeobfuscator;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.Console;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
                --padding MODE        on or off. Prompts for JAR output in an interactive terminal.
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
            int count = Protector.run(config, decision -> System.out.println(gson.toJson(decision)));
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
        if (hasFlag(arguments, "--dry-run")) {
            Coverage coverage = analyzeCoverage(arguments, gson);
            System.err.println("Eligible methods: " + coverage.methods.size());
            System.out.println(gson.toJson(Map.of("status", "dry-run-complete",
                "eligibleMethods", coverage.methods.size(), "writesOutput", false,
                "nextSteps", "Run harden without --dry-run to create and verify a staged JAR.")));
            return 0;
        }

        String[] protectArguments = arguments.clone();
        protectArguments[0] = "protect";
        var config = parse(protectArguments);
        if (config.emitC())
            throw new IllegalArgumentException("harden requires a staged JAR output; --emit-c is not supported");
        var decisions = new ArrayList<Protector.Decision>();
        int count = Protector.run(config, decision -> {
            decisions.add(decision);
            System.out.println(gson.toJson(decision));
        });
        var expected = new java.util.TreeSet<String>();
        decisions.stream().filter(decision -> decision.result().equals("selected"))
            .map(Protector.Decision::method).forEach(expected::add);
        var report = Workflow.verify(config.input(), config.output(), expected, targetKeys(config.targets()));
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
        Map<String, String> options = workflowOptions(arguments, true, false, false, false);
        String staged = required(options, "--staged");
        Coverage coverage = analyzeCoverage(arguments, gson);
        var report = Workflow.verify(coverage.config.input(), Path.of(staged), coverage.methods,
            verificationTargets(coverage.config));
        System.out.println(gson.toJson(Map.of("status", report.structurallyValid() ? "verified" : "verification-failed",
            "verification", report,
            "nextSteps", report.structurallyValid()
                ? "The JAR is structurally valid. Runtime equivalence still requires running your own application checks."
                : "Fix the reported structural issues before applying this JAR.")));
        return report.structurallyValid() ? 0 : 1;
    }

    private static int runApply(String[] arguments, Gson gson) throws IOException, InterruptedException {
        Map<String, String> options = workflowOptions(arguments, true, true, true, true);
        String staged = required(options, "--staged");
        String release = required(options, "--output");
        Coverage coverage = analyzeCoverage(arguments, gson);
        var verification = Workflow.verify(coverage.config.input(), Path.of(staged), coverage.methods,
            verificationTargets(coverage.config));
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
        Map<String, String> options = workflowOptions(arguments, false, true, true, false);
        String release = required(options, "--output");
        var result = Workflow.restore(Path.of(release), stateRoot(options));
        System.out.println(gson.toJson(Map.of("status", result.status(), "promotion", result)));
        return 0;
    }

    private static Coverage analyzeCoverage(String[] arguments, Gson gson)
            throws IOException, InterruptedException {
        Protector.Config config = parse(coverageArguments(arguments));
        var eligible = new java.util.TreeSet<String>();
        var decisions = new ArrayList<Protector.Decision>();
        Protector.run(config, decision -> {
            decisions.add(decision);
            System.out.println(gson.toJson(decision));
        });
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
        Set<String> retained = Set.of("--input", "--include", "--exclude", "--target",
            "--unsupported", "--min-instructions");
        Set<String> ignoredValues = Set.of("--output", "--staged", "--state-dir", "--zig",
            "--clang", "--llvm-opt", "--llvm-plugin", "--llvm-passes", "--profile", "--seed",
            "--max-growth", "--padding", "--markers", "--marker-seed");
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

    private static Map<String, String> workflowOptions(String[] arguments, boolean allowStaged,
            boolean allowOutput, boolean allowStateDir, boolean allowConfirm) {
        var options = new HashMap<String, String>();
        Set<String> coverageValues = Set.of("--input", "--include", "--exclude", "--target",
            "--unsupported", "--min-instructions");
        Set<String> ignoredValues = Set.of("--zig", "--clang", "--llvm-opt", "--llvm-plugin",
            "--llvm-passes", "--profile", "--seed", "--max-growth", "--padding", "--markers",
            "--marker-seed");
        for (int i = 1; i < arguments.length; i++) {
            String key = arguments[i];
            if (coverageValues.contains(key) || ignoredValues.contains(key)) {
                if (++i == arguments.length) throw new IllegalArgumentException("Missing value for " + key);
                continue;
            }
            if (key.equals("--staged") && allowStaged
                || key.equals("--output") && allowOutput
                || key.equals("--state-dir") && allowStateDir) {
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

    private static boolean hasFlag(String[] arguments, String flag) {
        for (String argument : arguments) if (argument.equals(flag)) return true;
        return false;
    }

    private static Protector.Config parse(String[] arguments) throws IOException {
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
            if (key.equals("--emit-c") && !emitC) {
                emitC = true;
                continue;
            }
            if (key.equals("--report-native-sizes") && !reportNativeSizes) {
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
                     "--llvm-passes", "--min-instructions", "--profile", "--seed", "--max-growth", "--padding", "--markers", "--marker-seed" -> {
                    if (options.putIfAbsent(key, value) != null)
                        throw new IllegalArgumentException("Repeated option " + key);
                }
                default -> throw new IllegalArgumentException("Unknown option " + key);
            }
        }
        if (includes.isEmpty()) throw new IllegalArgumentException("Select at least one --include pattern");
        Path input = path(options, "--input");
        Path output = analyze ? null : path(options, "--output");
        if (analyze && (options.containsKey("--output") || emitC))
            throw new IllegalArgumentException("Output options require protect");
        if (output != null && (input.equals(output) || (Files.exists(output) && Files.isSameFile(input, output))))
            throw new IllegalArgumentException("Input and output must be distinct files");
        boolean skip = switch (options.getOrDefault("--unsupported", "fail")) {
            case "fail" -> false;
            case "retain" -> true;
            default -> throw new IllegalArgumentException("Expected --unsupported fail or retain");
        };
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
        String passes = options.getOrDefault("--llvm-passes", preset);
        if (targets.isEmpty() && !analyze && !emitC) targets.add(NativeTarget.host());
        Path plugin = llvm && sites > 0 ? path(options, "--llvm-plugin") : null;
        int markers = Integer.parseInt(options.getOrDefault("--markers", "0"));
        long markerSeed = Long.parseUnsignedLong(options.getOrDefault("--marker-seed", "0"));
        if (markers < 0 || markers > 1000)
            throw new IllegalArgumentException("--markers must be between 0 and 1000");
        if ((markers > 0 && (analyze || emitC)) || (markers == 0 && options.containsKey("--marker-seed")))
            throw new IllegalArgumentException("Markers require protect JAR output and a positive --markers count");
        int minInstructions = Integer.parseInt(options.getOrDefault("--min-instructions", "0"));
        if (minInstructions < 0) throw new IllegalArgumentException("--min-instructions must be non-negative");
        if (reportNativeSizes && (analyze || emitC || targets.stream().noneMatch(t -> t.key.endsWith("-linux"))))
            throw new IllegalArgumentException("--report-native-sizes requires at least one Linux JAR target");
        boolean padding = parsePadding(options.get("--padding"), !analyze && !emitC);
        return new Protector.Config(input, output, List.copyOf(includes), List.copyOf(excludes), List.copyOf(targets),
            options.get("--zig"), skip, analyze, emitC, options.get("--clang"), options.get("--llvm-opt"),
            plugin, plugin == null ? null : passes, growth, markers, markerSeed, padding, minInstructions, reportNativeSizes);
    }

    private static boolean parsePadding(String mode, boolean jarOutput) {
        if (!jarOutput) {
            if (mode != null) throw new IllegalArgumentException("--padding requires protect JAR output");
            return false;
        }
        if (mode != null) {
            return switch (mode) {
                case "on" -> true;
                case "off" -> false;
                default -> throw new IllegalArgumentException("--padding must be on or off");
            };
        }

        Console console = System.console();
        if (!isTerminal(console)) return false;
        System.err.printf("Add %d standalone utility classes as file padding? [y/N] ", FilePadding.classCount());
        System.err.flush();
        String answer = console.readLine();
        return answer != null && (answer.strip().equalsIgnoreCase("y") || answer.strip().equalsIgnoreCase("yes"));
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
            var fields = JsonParser.parseString(content).getAsJsonObject();
            for (var field : fields.entrySet()) {
                String key = field.getKey();
                JsonElement value = field.getValue();
                String flag = "--" + key;
                if (key.equals("include") || key.equals("exclude") || key.equals("target")) {
                    for (JsonElement item : value.getAsJsonArray()) {
                        args.add(flag);
                        args.add(stringValue(item));
                    }
                } else if (key.equals("emit-c") || key.equals("report-native-sizes")) {
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
