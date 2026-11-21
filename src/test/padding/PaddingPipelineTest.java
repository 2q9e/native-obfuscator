package dev.nativeobfuscator;

import com.google.gson.GsonBuilder;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import static org.objectweb.asm.Opcodes.*;

/** Local differential/build matrix. Outputs and maintainer inventory stay under build/. */
public final class PaddingPipelineTest {
    private static final long SEED = 77236712L;
    private static final List<Map<String, Object>> RESULTS = new ArrayList<>();
    private static int assertions;

    public static void main(String[] args) throws Exception {
        if (args.length < 3) throw new IllegalArgumentException("Usage: PaddingPipelineTest original.jar output-dir zig [clang opt plugin passes]");
        Path input = Path.of(args[0]).toAbsolutePath();
        Path work = Files.createDirectories(Path.of(args[1]).toAbsolutePath());
        String zig = args[2];
        String clang = args.length > 3 ? args[3] : null;
        String opt = args.length > 4 ? args[4] : null;
        Path plugin = args.length > 5 ? Path.of(args[5]) : null;
        String passes = args.length > 6 ? args[6] : "native-encode<seed=42;max=4;prefix=method_>";
        cliTests(input, work);
        Map<String, byte[]> original = Archive.read(input);
        generatorTests(original);
        String baseline = applicationOutput(input);
        for (int count : new int[] {0, 1, 12, 1000}) {
            var targets = count == 1000 ? List.of(NativeTarget.values()) : List.of(NativeTarget.host());
            Path output = work.resolve("padding-" + count + ".jar");
            var config = new Protector.Config(input, output, List.of(Pattern.compile("Arithmetic#calcOverload.*")),
                List.of(), targets, zig, true, false, false, clang, opt, plugin,
                plugin == null ? null : passes, -1, 0, 0, count, SEED, 0, false);
            var decisions = new ArrayList<Protector.Decision>();
            long started = System.nanoTime();
            Protector.run(config, decisions::add);
            double seconds = (System.nanoTime() - started) / 1e9;
            var selected = new TreeSet<String>();
            decisions.stream().filter(d -> d.result().equals("selected")).map(Protector.Decision::method).forEach(selected::add);
            var genuineSelection = genuineSelection(original, selected);
            var generated = FilePadding.generate(original, count, SEED, genuineSelection);
            require(selected.size() == genuineSelection.size() + generated.nativeMethods().size()
                && selected.containsAll(generated.nativeMethods().keySet()), "Unexpected generated method selection");
            var entries = Archive.read(output);
            long addedClasses = entries.keySet().stream().filter(n -> n.endsWith(".class") && !original.containsKey(n)).count();
            require(addedClasses == count + 1L, "Expected generated classes plus exactly one runtime: " + addedClasses);
            require(entries.keySet().containsAll(generated.classes().keySet()), "Generated class set mismatch");
            for (String entry : generated.classes().keySet()) {
                var node = node(entries.get(entry));
                require(node.methods.stream().anyMatch(m -> (m.access & ACC_NATIVE) != 0), "No native methods: " + entry);
                require(node.version == node(generated.classes().get(entry)).version, "Classfile version changed");
            }
            var targetKeys = new TreeSet<String>();
            targets.forEach(t -> targetKeys.add(t.key));
            var verification = Workflow.verify(input, output, selected, targetKeys, count, SEED);
            require(verification.structurallyValid(), "Workflow verification: " + verification.errors());
            require(verification.nativeLibraryCount() == targets.size(), "Target count mismatch");
            require(Set.copyOf(verification.expectedGeneratedClasses()).equals(generated.classes().keySet()), "Verification inventory mismatch");
            require(applicationOutput(output).equals(baseline), "Application differential failed for " + count);
            var differential = generatedDifferential(generated.classes(), generated.nativeMethods(), output);
            Files.write(work.resolve("inventory-" + count + ".txt"), verification.expectedGeneratedClasses(), StandardCharsets.UTF_8);
            if (count == 12) {
                var broad = new Protector.Config(input, null, List.of(Pattern.compile(".*")),
                    List.of(Pattern.compile("Arithmetic#(?!calcOverload).*"), Pattern.compile("Arithmetic\\$.*")),
                    targets, zig, false, true, false, null, null, null, null, -1, 0, 0, count, SEED, 0, false);
                var broadSelection = new TreeSet<String>();
                Protector.run(broad, d -> { if (d.result().equals("eligible")) broadSelection.add(d.method()); });
                require(selected.equals(broadSelection), "Broad application selector changed the generated method plan");
                tamperTests(input, output, work, selected, targetKeys, count);
                workflowCliTests(input, output, work, zig);
                Path duplicate = work.resolve("padding-12-repeat.jar");
                var repeat = new Protector.Config(input, duplicate, config.includes(), config.excludes(), config.targets(),
                    zig, true, false, false, clang, opt, plugin, config.llvmPasses(), -1, 0, 0,
                    count, SEED, 0, false);
                Protector.run(repeat, d -> {});
                require(Arrays.equals(Files.readAllBytes(output), Files.readAllBytes(duplicate)), "Fixed seed archive reproducibility");
            }
            var result = new LinkedHashMap<String, Object>();
            result.put("paddingCount", count);
            result.put("paddingSeed", SEED);
            result.put("profile", plugin == null ? "compatibility" : passes);
            result.put("buildSeconds", seconds);
            result.put("inputJarBytes", Files.size(input));
            result.put("outputJarBytes", Files.size(output));
            result.put("jarGrowthBytes", Files.size(output) - Files.size(input));
            result.put("generatedClassEntries", count);
            result.put("runtimeClassEntries", 1);
            result.put("nativeMethods", selected.size());
            result.put("executedTargets", List.of(NativeTarget.host().key));
            result.put("crossBuiltOnlyTargets", targets.stream().filter(t -> t != NativeTarget.host()).map(t -> t.key).toList());
            result.put("differential", differential);
            result.put("generatedFinalClasses", statistics(entries, generated.classes().keySet()));
            Set<String> real = new TreeSet<>();
            Set<String> realNative = new TreeSet<>();
            for (var e : original.entrySet()) if (e.getKey().endsWith(".class")) {
                real.add(e.getKey());
                if (node(entries.get(e.getKey())).methods.stream().anyMatch(m -> (m.access & ACC_NATIVE) != 0)) realNative.add(e.getKey());
            }
            result.put("genuineFinalClasses", statistics(entries, real));
            result.put("genuineFinalNativeClasses", statistics(entries, realNative));
            RESULTS.add(result);
            Files.writeString(work.resolve("measurements.json"), new GsonBuilder().setPrettyPrinting().create().toJson(Map.of(
                "javaVersion", System.getProperty("java.version"), "os", System.getProperty("os.name"),
                "assertions", assertions, "results", RESULTS)));
            System.out.println("PASS padding=" + count + " selected=" + selected.size() + " seconds=" + seconds);
        }
        System.out.println("PASS padding pipeline assertions=" + assertions);
    }

    private static void cliTests(Path input, Path work) throws Exception {
        require(parse(input, work).paddingCount() == 0, "Noninteractive default must be off");
        require(parse(input, work, "--padding", "off").paddingCount() == 0, "Legacy off");
        require(parse(input, work, "--padding", "on").paddingCount() == 12, "Legacy on");
        for (int count : new int[] {0, 1, 12, 1000})
            require(parse(input, work, "--padding-count", "" + count).paddingCount() == count, "CLI count " + count);
        for (String count : List.of("-1", "1001", "1.0", "NaN", "2147483648", ""))
            invalidParse(input, work, "--padding-count", count);
        invalidParse(input, work, "--padding", "off", "--padding-count", "1");
        invalidParse(input, work, "--padding", "on", "--padding-count", "0");
        invalidParse(input, work, "--padding-count", "1", "--padding-count", "2");
        invalidParse(input, work, "--padding-seed", "9223372036854775808");
        Path config = work.resolve("test-config.json");
        Files.writeString(config, new GsonBuilder().create().toJson(Map.of("input", input.toString(),
            "output", work.resolve("config.jar").toString(), "include", List.of("Arithmetic#calcInt"),
            "paddingCount", 12, "paddingSeed", SEED)));
        Method parser = Main.class.getDeclaredMethod("parse", String[].class);
        parser.setAccessible(true);
        var parsed = (Protector.Config) parser.invoke(null, (Object) new String[] {"protect", "--config", config.toString()});
        require(parsed.paddingCount() == 12 && parsed.paddingSeed() == SEED, "JSON count/seed equivalence");
        Files.writeString(config, new GsonBuilder().create().toJson(Map.of("input", input.toString(),
            "output", work.resolve("config.jar").toString(), "include", List.of("Arithmetic#calcOverload*"),
            "padding-count", 1, "padding-seed", SEED)));
        parsed = (Protector.Config) parser.invoke(null, (Object) new String[] {"protect", "--config", config.toString()});
        require(parsed.paddingCount() == 1 && parsed.paddingSeed() == SEED, "JSON kebab-case aliases");
        for (String invalid : List.of("-1", "1001", "1.5", "\"bad\"")) {
            Files.writeString(config, "{\"input\":" + new com.google.gson.Gson().toJson(input.toString())
                + ",\"output\":" + new com.google.gson.Gson().toJson(work.resolve("config.jar").toString())
                + ",\"include\":[\"Arithmetic#calcOverload*\"],\"paddingCount\":" + invalid + "}");
            try { parser.invoke(null, (Object) new String[] {"protect", "--config", config.toString()}); throw new AssertionError("Accepted JSON invalid count"); }
            catch (InvocationTargetException expected) { require(expected.getCause() instanceof IllegalArgumentException || expected.getCause() instanceof java.io.IOException, "JSON invalid diagnostic"); }
        }
    }

    private static Protector.Config parse(Path input, Path work, String... extra) throws Exception {
        var args = new ArrayList<>(List.of("protect", "--input", input.toString(), "--output",
            work.resolve("parse.jar").toString(), "--include", "Arithmetic#calcInt*"));
        args.addAll(List.of(extra));
        Method parser = Main.class.getDeclaredMethod("parse", String[].class);
        parser.setAccessible(true);
        try { return (Protector.Config) parser.invoke(null, (Object) args.toArray(String[]::new)); }
        catch (InvocationTargetException e) { if (e.getCause() instanceof Exception x) throw x; throw e; }
    }

    private static void invalidParse(Path input, Path work, String... extra) throws Exception {
        try { parse(input, work, extra); throw new AssertionError("Accepted invalid options " + List.of(extra)); }
        catch (IllegalArgumentException expected) { require(expected.getMessage() != null && !expected.getMessage().isBlank(), "Missing diagnostic"); }
    }

    private static void generatorTests(Map<String, byte[]> input) throws Exception {
        require(FilePadding.generate(input, 0, SEED).classes().isEmpty(), "Disabled generator");
        for (int invalid : new int[] {-1, 1001}) {
            try { FilePadding.generate(input, invalid, SEED); throw new AssertionError("Invalid generator count"); }
            catch (IllegalArgumentException expected) { assertions++; }
        }
        var a = FilePadding.generate(input, 12, SEED);
        var b = FilePadding.generate(input, 12, SEED);
        require(a.classes().keySet().equals(b.classes().keySet()), "Name determinism");
        for (String name : a.classes().keySet()) require(Arrays.equals(a.classes().get(name), b.classes().get(name)), "Body determinism");
        var changed = FilePadding.generate(input, 12, SEED + 1);
        require(!a.classes().keySet().equals(changed.classes().keySet()), "Padding seed changes class set");
        var collided = new LinkedHashMap<>(input);
        collided.putAll(a.classes());
        var c = FilePadding.generate(collided, 12, SEED);
        require(c.classes().keySet().stream().noneMatch(collided::containsKey), "Name collision against input");
        var versions = new TreeSet<Integer>();
        input.forEach((n, bytes) -> { if (n.endsWith(".class")) versions.add(node(bytes).version); });
        a.classes().values().forEach(bytes -> require(versions.contains(node(bytes).version), "Input-supported classfile version"));
    }

    private static Map<String, Object> generatedDifferential(Map<String, byte[]> originals,
            Map<String, String> nativeMethods, Path jar) throws Exception {
        var families = new TreeSet<String>();
        int calls = 0;
        try (var nativeLoader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            ClassLoader javaLoader = new ClassLoader(ClassLoader.getPlatformClassLoader()) {
                @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                    byte[] bytes = originals.get(name.replace('.', '/') + ".class");
                    if (bytes == null) throw new ClassNotFoundException(name);
                    return defineClass(name, bytes, 0, bytes.length);
                }
            };
            for (var entry : nativeMethods.entrySet()) {
                String id = entry.getKey();
                int separator = id.indexOf('#'), descriptor = id.indexOf('(', separator);
                String owner = id.substring(0, separator).replace('/', '.');
                String name = id.substring(separator + 1, descriptor);
                String desc = id.substring(descriptor);
                Class<?> expectedClass = javaLoader.loadClass(owner), actualClass = nativeLoader.loadClass(owner);
                Method expected = findMethod(expectedClass, name, desc), actual = findMethod(actualClass, name, desc);
                expected.setAccessible(true); actual.setAccessible(true);
                require(java.lang.reflect.Modifier.isNative(actual.getModifiers()), "Selected generated method remained Java: " + id);
                for (int sample = 0; sample < 24; sample++) {
                    Object[] left = arguments(expected.getParameterTypes(), sample);
                    Object[] right = arguments(actual.getParameterTypes(), sample);
                    Object e = outcome(expected, left), a = outcome(actual, right);
                    require(java.util.Objects.deepEquals(e, a), "Generated differential " + id + " sample=" + sample + " java=" + e + " native=" + a);
                    for (int i = 0; i < left.length; i++) require(java.util.Objects.deepEquals(left[i], right[i]), "Array side effect mismatch " + id);
                    calls++;
                }
                families.add(entry.getValue());
            }
        }
        return Map.of("calls", calls, "families", families, "methods", nativeMethods.size(), "samplesPerMethod", 24);
    }

    private static Method findMethod(Class<?> owner, String name, String descriptor) {
        return Arrays.stream(owner.getDeclaredMethods()).filter(m -> m.getName().equals(name)
            && Type.getMethodDescriptor(m).equals(descriptor)).findFirst().orElseThrow();
    }

    private static Object outcome(Method method, Object[] args) throws Exception {
        try { return method.invoke(null, args); }
        catch (InvocationTargetException e) { return "exception:" + e.getCause().getClass().getName(); }
    }

    private static Object[] arguments(Class<?>[] types, int sample) {
        long[] values = {0, 1, -1, 2, -7, 31, 255, 1024, Integer.MIN_VALUE, Integer.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE};
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            Class<?> type = types[i]; long value = values[(sample + i * 3) % values.length];
            if (type == int.class) args[i] = (int) value;
            else if (type == long.class) args[i] = value;
            else if (type == boolean.class) args[i] = (value & 1) != 0;
            else if (type == byte.class) args[i] = (byte) value;
            else if (type == short.class) args[i] = (short) value;
            else if (type == char.class) args[i] = (char) value;
            else if (type == float.class) args[i] = (float) value;
            else if (type == double.class) args[i] = (double) value;
            else if (type == int[].class) {
                if (sample % 4 == 0) args[i] = null;
                else {
                    int[] data = new int[sample % 7];
                    for (int j = 0; j < data.length; j++) data[j] = (int) values[(sample + j * 5) % values.length];
                    args[i] = data;
                }
            }
            else if (type.isArray()) args[i] = sample % 4 == 0 ? null : Array.newInstance(type.getComponentType(), sample % 5);
            else throw new AssertionError("Unconfigured generated argument " + type);
        }
        return args;
    }

    private static void tamperTests(Path input, Path output, Path work, Set<String> selected,
            Set<String> targets, int count) throws Exception {
        var entries = Archive.read(output);
        var unexpected = new LinkedHashMap<>(entries);
        unexpected.put("extra.bin", new byte[] {1});
        Path bad = work.resolve("unexpected.jar"); Archive.write(unexpected, bad);
        require(!Workflow.verify(input, bad, selected, targets, count, SEED).structurallyValid(), "Accepted unexpected archive entry");
        var original = Archive.read(input);
        var generated = FilePadding.generate(original, count, SEED, genuineSelection(original, selected));
        String first = generated.classes().keySet().iterator().next();
        var modified = new LinkedHashMap<>(entries);
        var node = node(modified.get(first));
        node.methods.stream().filter(m -> !m.name.startsWith("<") && (m.access & ACC_NATIVE) == 0 && m.instructions.size() > 0)
            .findFirst().ifPresent(m -> m.instructions.insert(new InsnNode(NOP)));
        var writer = new org.objectweb.asm.ClassWriter(0); node.accept(writer); modified.put(first, writer.toByteArray());
        Archive.write(modified, bad);
        require(!Workflow.verify(input, bad, selected, targets, count, SEED).structurallyValid(), "Accepted altered generated Java body");
        var removed = new LinkedHashMap<>(entries); removed.remove(first); Archive.write(removed, bad);
        require(!Workflow.verify(input, bad, selected, targets, count, SEED).structurallyValid(), "Accepted missing generated class");
        var extraNative = new LinkedHashMap<>(entries); node = node(extraNative.get(first));
        var retained = node.methods.stream().filter(m -> !m.name.startsWith("<") && (m.access & ACC_NATIVE) == 0).findFirst().orElseThrow();
        retained.access |= ACC_NATIVE; retained.instructions.clear(); retained.tryCatchBlocks.clear(); retained.localVariables = null;
        writer = new org.objectweb.asm.ClassWriter(0); node.accept(writer); extraNative.put(first, writer.toByteArray());
        Archive.write(extraNative, bad);
        require(!Workflow.verify(input, bad, selected, targets, count, SEED).structurallyValid(), "Accepted unexpected generated native binding");
        var wrongImage = new LinkedHashMap<>(entries);
        String library = entries.keySet().stream().filter(name -> name.endsWith(".dll") || name.endsWith(".so"))
            .findFirst().orElseThrow();
        byte[] malformedLibrary = {1};
        wrongImage.put(library, malformedLibrary);
        wrongImage.put(library + ".sha256", java.util.HexFormat.of().formatHex(Archive.sha256().digest(malformedLibrary))
            .getBytes(StandardCharsets.US_ASCII));
        Archive.write(wrongImage, bad);
        require(Workflow.verify(input, bad, selected, targets, count, SEED).errors().stream()
            .anyMatch(error -> error.startsWith("Invalid native library for ")), "Accepted invalid native image with matching digest");
        var wrongRegistrationPath = new LinkedHashMap<>(entries);
        String registration = entries.keySet().stream().filter(name -> name.endsWith("/registration/0.bin"))
            .findFirst().orElseThrow();
        wrongRegistrationPath.put(registration.replace("/0.bin", "/00.bin"), wrongRegistrationPath.remove(registration));
        Archive.write(wrongRegistrationPath, bad);
        require(!Workflow.verify(input, bad, selected, targets, count, SEED).structurallyValid(), "Accepted noncanonical registration path");
        require(!Workflow.verify(input, output, selected, targets, count, SEED + 1).structurallyValid(), "Accepted wrong padding seed");
        require(!Workflow.verify(input, output, selected, targets, 0, SEED).structurallyValid(), "Accepted wrong expected count");
    }

    private static void workflowCliTests(Path input, Path staged, Path work, String zig) throws Exception {
        require(runMain("verify", "--input", input.toString(), "--staged", staged.toString(), "--include", "Arithmetic#calcOverload*",
            "--unsupported", "retain", "--padding-count", "12", "--padding-seed", "" + SEED) == 0, "CLI verify");
        Path configFile = work.resolve("verify-config.json");
        var configValues = new LinkedHashMap<String, Object>();
        configValues.put("input", input.toString()); configValues.put("staged", staged.toString());
        configValues.put("include", List.of("Arithmetic#calcOverload*")); configValues.put("unsupported", "retain");
        configValues.put("paddingCount", 12); configValues.put("paddingSeed", SEED);
        Files.writeString(configFile, new com.google.gson.Gson().toJson(configValues));
        require(runMain("verify", "--config", configFile.toString()) == 0, "CLI verify JSON config");
        Path release = work.resolve("release.jar");
        Path state = work.resolve("state");
        require(runMain("apply", "--input", input.toString(), "--staged", staged.toString(), "--output", release.toString(),
            "--include", "Arithmetic#calcOverload*", "--unsupported", "retain", "--padding-count", "12", "--padding-seed", "" + SEED,
            "--state-dir", state.toString(), "--confirm-overwrite") == 0, "CLI apply");
        require(Arrays.equals(Files.readAllBytes(release), Files.readAllBytes(staged)), "Apply content");
        require(runMain("restore", "--output", release.toString(), "--state-dir", state.toString()) == 0, "CLI restore");
        configValues.put("output", release.toString()); configValues.put("state-dir", state.toString());
        configValues.put("confirm-overwrite", true);
        Files.writeString(configFile, new com.google.gson.Gson().toJson(configValues));
        require(runMain("apply", "--config", configFile.toString()) == 0, "CLI apply JSON config");
        require(runMain("restore", "--output", release.toString(), "--state-dir", state.toString()) == 0, "CLI restore config promotion");
        Path hardened = work.resolve("hardened.jar");
        require(runMain("harden", "--input", input.toString(), "--output", hardened.toString(), "--include", "Arithmetic#calcOverload*",
            "--unsupported", "retain", "--padding-count", "1", "--padding-seed", "" + SEED, "--zig", zig) == 0, "CLI harden");
    }

    private static int runMain(String... args) throws Exception {
        Method run = Main.class.getDeclaredMethod("run", String[].class); run.setAccessible(true);
        return (int) run.invoke(null, (Object) args);
    }

    private static String applicationOutput(Path jar) throws Exception {
        var bytes = new ByteArrayOutputStream(); PrintStream previous = System.out;
        try (var loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
             var capture = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            loader.loadClass("Arithmetic").getMethod("main", String[].class).invoke(null, (Object) new String[0]);
        } finally { System.setOut(previous); }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static Map<String, Object> statistics(Map<String, byte[]> entries, Set<String> names) {
        List<Integer> sizes = new ArrayList<>(), methods = new ArrayList<>(), fields = new ArrayList<>(), natives = new ArrayList<>(), javaMethods = new ArrayList<>();
        for (String name : names) {
            byte[] bytes = entries.get(name); var node = node(bytes);
            sizes.add(bytes.length); methods.add(node.methods.size()); fields.add(node.fields.size());
            int nativeCount = (int) node.methods.stream().filter(m -> (m.access & ACC_NATIVE) != 0).count();
            natives.add(nativeCount); javaMethods.add(node.methods.size() - nativeCount);
        }
        return Map.of("classes", names.size(), "uncompressedBytes", distribution(sizes), "methodCounts", distribution(methods),
            "fieldCounts", distribution(fields), "nativeMethodCounts", distribution(natives), "javaMethodCounts", distribution(javaMethods));
    }

    private static Map<String, Object> distribution(List<Integer> values) {
        values.sort(Integer::compareTo);
        if (values.isEmpty()) return Map.of("count", 0);
        int n = values.size();
        return Map.of("count", n, "min", values.get(0), "p10", values.get((n - 1) / 10), "p50", values.get((n - 1) / 2),
            "p90", values.get((n - 1) * 9 / 10), "max", values.get(n - 1), "mean", values.stream().mapToInt(i -> i).average().orElse(0));
    }

    private static ClassNode node(byte[] bytes) { var node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node; }
    private static Set<String> genuineSelection(Map<String, byte[]> original, Set<String> selected) {
        var result = new TreeSet<String>();
        for (String method : selected) if (original.containsKey(method.substring(0, method.indexOf('#')) + ".class")) result.add(method);
        return result;
    }
    private static void require(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}
