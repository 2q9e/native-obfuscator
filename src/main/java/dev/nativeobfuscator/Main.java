package dev.nativeobfuscator;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class Main {
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

                --include GLOB        Select an owner#name(descriptor) signature. Repeatable.
                --exclude GLOB        Retain a matching signature in Java. Repeatable.
                --unsupported POLICY  fail (default) or retain.
                --target TARGET       Repeatable. x86_64-linux, aarch64-linux,
                                      x86_64-windows, aarch64-windows, or all.
                --zig PATH            Zig executable for cross-compilation. Host Linux can use CC.
                --config PATH         Read options from a JSON file instead of command-line options.
                --profile PROFILE     compatibility, balanced, aggressive or maximum.
                --seed UNSIGNED        Reproducible LLVM build seed (default 0).
                --max-growth PERCENT  Reject libraries above this growth relative to LLVM baseline.
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
        for (int i = 1; i < arguments.length; i++) {
            String key = arguments[i];
            if (key.equals("--emit-c") && !emitC) {
                emitC = true;
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
                     "--llvm-passes", "--profile", "--seed", "--max-growth" -> {
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
        return new Protector.Config(input, output, List.copyOf(includes), List.copyOf(excludes), List.copyOf(targets),
            options.get("--zig"), skip, analyze, emitC, options.get("--clang"), options.get("--llvm-opt"),
            plugin, plugin == null ? null : passes, growth);
    }

    private static String[] loadConfig(String action, Path path) throws IOException {
        if (Files.size(path) > 1024 * 1024) throw new IOException("Config file exceeds size limit");
        var args = new ArrayList<>(List.of(action));
        try {
            var fields = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            for (var field : fields.entrySet()) {
                String key = field.getKey();
                JsonElement value = field.getValue();
                String flag = "--" + key;
                if (key.equals("include") || key.equals("exclude") || key.equals("target")) {
                    for (JsonElement item : value.getAsJsonArray()) {
                        args.add(flag);
                        args.add(stringValue(item));
                    }
                } else if (key.equals("emit-c")) {
                    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
                        throw new IllegalArgumentException("emit-c must be a boolean");
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
