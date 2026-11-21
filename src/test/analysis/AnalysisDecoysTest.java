package dev.nativeobfuscator;

import dev.nativeobfuscator.translate.MethodCompiler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;

import static org.objectweb.asm.Opcodes.*;

/** Standalone JVM behavior, generator determinism, JNI lowering and rewrite checks. */
public final class AnalysisDecoysTest {
    private static final long SEED = 0x389ade25302eeb22L;

    public static void main(String[] args) throws Exception {
        var empty = AnalysisDecoys.generate(Map.of(), 0, SEED);
        require(empty.classes().isEmpty() && empty.nativeMethods().isEmpty(), "Zero budget generated classes");
        for (int count : new int[]{-1, 17, Integer.MAX_VALUE}) {
            try {
                AnalysisDecoys.generate(Map.of(), count, SEED);
                throw new AssertionError("Accepted invalid method count " + count);
            } catch (IllegalArgumentException expected) {
                // Count validation must precede reference inspection.
            }
        }
        var inputs = new LinkedHashMap<String, byte[]>();
        inputs.put("orchard/tools/Reference.class", reference("orchard/tools/Reference", V1_8, ACC_PUBLIC));
        inputs.put("orchard/tools/package-info.class", reference("orchard/tools/package-info", V17, ACC_PUBLIC));
        inputs.put("java/unsafe/Reference.class", reference("java/unsafe/Reference", V17, ACC_PUBLIC));
        inputs.put("other/Interface.class", reference("other/Interface", V17, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT));
        inputs.put("META-INF/versions/17/other/Versioned.class", reference("other/Versioned", V17, ACC_PUBLIC));
        inputs.put("META-INF/versions/999/other/Future.class", new byte[0]);
        inputs.put("META-INF/fabric/Detached.class", reference("other/Detached", V17, ACC_PUBLIC));

        var all = AnalysisDecoys.generate(inputs, AnalysisDecoys.MAX_METHODS, SEED);
        require(all.classes().size() == 16 && all.nativeMethods().size() == 16, "Wrong maximum budget");
        require(new HashSet<>(all.nativeMethods().values()).size() == 16, "Repeated algorithm");
        var locale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            sameBytes(all, AnalysisDecoys.generate(inputs, AnalysisDecoys.MAX_METHODS, SEED));
        } finally {
            Locale.setDefault(locale);
        }
        var reordered = new LinkedHashMap<>(inputs.reversed());
        sameBytes(all, AnalysisDecoys.generate(reordered, AnalysisDecoys.MAX_METHODS, SEED));
        var differentSeed = AnalysisDecoys.generate(inputs, AnalysisDecoys.MAX_METHODS, SEED ^ Long.MIN_VALUE);
        require(!new ArrayList<>(all.classes().keySet()).equals(new ArrayList<>(differentSeed.classes().keySet())),
            "Upper seed bits made no difference");

        var prefix = AnalysisDecoys.generate(inputs, 5, SEED);
        require(new ArrayList<>(prefix.classes().keySet()).equals(new ArrayList<>(all.classes().keySet()).subList(0, 5)),
            "Larger method budget replaced the earlier candidates");
        prefix.classes().forEach((name, bytes) -> require(Arrays.equals(bytes, all.classes().get(name)),
            "Larger method budget changed bytes for " + name));

        var withConflicts = new LinkedHashMap<>(inputs);
        String first = all.classes().keySet().iterator().next();
        withConflicts.put("META-INF/versions/17/" + first, all.classes().get(first));
        var versioned = AnalysisDecoys.generate(withConflicts, 1, SEED);
        require(!versioned.classes().containsKey(first), "Collision with a versioned class");
        withConflicts.put("META-INF/fabric/Taken.class", versioned.classes().values().iterator().next());
        String second = versioned.classes().keySet().iterator().next();
        var reserved = AnalysisDecoys.generate(withConflicts, 1, SEED);
        require(!reserved.classes().containsKey(first) && !reserved.classes().containsKey(second),
            "Collision with a binary owner");
        sameBytes(all, AnalysisDecoys.generate(inputs, AnalysisDecoys.MAX_METHODS, SEED, Set.of("other/Bonus.class")));
        require(!AnalysisDecoys.generate(inputs, 1, SEED, Set.of(first)).classes().containsKey(first),
            "Collision with a separately reserved path");

        var noPackage = AnalysisDecoys.generate(Map.of("Reference.class", reference("Reference", V1_8, ACC_PUBLIC)),
            1, SEED);
        require(noPackage.classes().keySet().stream().noneMatch(path -> path.contains("/")), "Default package lost");
        var fallback = AnalysisDecoys.generate(Map.of(), 1, SEED);
        require(fallback.classes().keySet().stream().allMatch(path -> path.startsWith("utility/")),
            "Fallback package unavailable");

        var sourceLoader = new Loader();
        var rewrittenLoader = new Loader();
        var shapes = new HashSet<List<Integer>>();
        for (var generated : all.classes().entrySet()) {
            var owner = read(generated.getValue());
            require(owner.version == V1_8 && generated.getKey().equals(owner.name + ".class")
                && owner.name.startsWith("orchard/tools/"), "Wrong owner or package reference");
            require(owner.nestHostClass == null && owner.innerClasses.isEmpty() && owner.sourceFile == null,
                "Template metadata reached generated bytecode");
            var keys = all.nativeMethods().keySet().stream().filter(key -> key.startsWith(owner.name + "#")).toList();
            require(keys.size() == 1, "Wrong number of native entries for " + owner.name);
            String signature = keys.getFirst().substring(owner.name.length() + 1);
            var entry = owner.methods.stream().filter(method -> (method.name + method.desc).equals(signature))
                .findFirst().orElseThrow();
            require(entry.instructions.size() <= 300 && entry.tryCatchBlocks.isEmpty(), "Entry exceeds bytecode budget");
            List<Integer> opcodes = Arrays.stream(entry.instructions.toArray()).map(AbstractInsnNode::getOpcode)
                .filter(opcode -> opcode >= 0).toList();
            require(shapes.add(opcodes), "Reused normalized instruction shape");
            for (var method : owner.methods) {
                if (!method.name.startsWith("<")) {
                    String compiled = MethodCompiler.compile(owner, method, "utility_entry");
                    require(compiled.contains("JNICALL utility_entry"), "Method compiler did not emit an entry");
                }
            }

            Class<?> type = sourceLoader.define(generated.getValue());
            Method callable = Arrays.stream(type.getDeclaredMethods()).filter(method ->
                (method.getName() + Type.getMethodDescriptor(method)).equals(signature)).findFirst().orElseThrow();
            checkBehavior(all.nativeMethods().get(keys.getFirst()), callable);

            byte[] rewritten = Protector.rewriteExpected(generated.getValue(), "fixture/Registration", 0, Set.of(signature));
            var modified = read(rewritten);
            var nativeEntries = modified.methods.stream().filter(method -> (method.access & ACC_NATIVE) != 0).toList();
            require(nativeEntries.size() == 1 && signature.equals(nativeEntries.getFirst().name + nativeEntries.getFirst().desc)
                && nativeEntries.getFirst().instructions.size() == 0, "JNI rewrite stripped the wrong method");
            require(modified.methods.stream().anyMatch(method -> method.name.equals("<clinit>")),
                "JNI registration initializer missing");
            Class<?> nativeClass = rewrittenLoader.define(rewritten);
            require(Arrays.stream(nativeClass.getDeclaredMethods()).filter(method -> Modifier.isNative(method.getModifiers()))
                .count() == 1, "Rewritten class did not load with one native entry");
        }
        System.out.println("PASS analysis utility generation, behavior, JNI lowering and rewrite");
    }

    private static void checkBehavior(String utility, Method method) throws Exception {
        switch (utility) {
            case "weighted-mean" -> {
                expect(method, 8L, new int[]{2, 10, 100}, new int[]{1, 3, -1});
                expect(method, 0L, null, new int[]{1});
                expect(method, (long) Integer.MIN_VALUE, new int[]{Integer.MIN_VALUE}, new int[]{Integer.MAX_VALUE});
            }
            case "sample-variance" -> {
                expect(method, 2.0, new short[]{1, 2, 3, 4, 5, 32767}, 5);
                expect(method, 0.0, new short[]{32767}, -1);
            }
            case "lower-bound" -> {
                expect(method, 1, new long[]{Long.MIN_VALUE, -5, -5, 7}, -5L);
                expect(method, 4, new long[]{Long.MIN_VALUE, -5, -5, 7}, Long.MAX_VALUE);
                expect(method, 4096, new long[5000], 1L);
            }
            case "longest-run" -> {
                expect(method, 3, new byte[]{-1, -1, 1, -1, -1, -1}, (byte) -1);
                expect(method, 256, new byte[512], (byte) 0);
            }
            case "saturating-add" -> {
                expect(method, 9L, -2L, 9L, Long.MAX_VALUE, 1);
                expect(method, -2L, -2L, 9L, Long.MIN_VALUE, -1);
                expect(method, 7L, -2L, 9L, 3L, 4);
            }
            case "interval-coverage" -> {
                expect(method, 11L, new int[]{-3, -2, 8}, new int[]{2, 5, 11});
                expect(method, 4294967295L, new int[]{Integer.MIN_VALUE}, new int[]{Integer.MAX_VALUE});
                expect(method, -1L, new int[]{3, 2}, new int[]{4, 3});
            }
            case "prefix-totals" -> {
                var totals = new int[2];
                expect(method, 2, new short[]{3, -7, 4}, totals, Integer.MAX_VALUE - 1);
                require(Arrays.equals(totals, new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE - 7}), "Incorrect prefix totals");
            }
            case "window-copy" -> {
                var values = new byte[]{1, 2, 3, 4, 5};
                expect(method, 3, values, 0, values, 1, 3);
                require(Arrays.equals(values, new byte[]{1, 1, 2, 3, 5}), "Incorrect backward overlap");
                expect(method, 3, values, 1, values, 0, 3);
                require(Arrays.equals(values, new byte[]{1, 2, 3, 3, 5}), "Incorrect forward overlap");
                expect(method, 128, new byte[256], 0, new byte[256], 0, 200);
            }
            case "cursor-transition" -> {
                var type = method.getDeclaringClass();
                Object cursor = type.getConstructor(int.class).newInstance(7);
                require(method.invoke(cursor, 5, 0).equals(5), "Incorrect absolute position");
                require(method.invoke(cursor, Integer.MAX_VALUE, 1).equals(7), "Incorrect clamped position");
                require(method.invoke(cursor, -9, 2).equals(6), "Incorrect wrapped position");
                require(method.invoke(cursor, 0, 3).equals(6), "Unknown mode changed the cursor");
                require(type.getMethod("position").invoke(cursor).equals(6), "Position was not retained");
                Object empty = type.getConstructor(int.class).newInstance(-1);
                require(method.invoke(empty, 9, 2).equals(0), "Negative limit created an invalid position");
            }
            case "matrix-index" -> {
                expect(method, 17L, 2, 3, 5, 7);
                expect(method, 2147483647L * 2147483646L + 1, Integer.MAX_VALUE - 1, 1, Integer.MAX_VALUE, Integer.MAX_VALUE);
                expect(method, -1L, 0, 4, 2, 4);
            }
            case "linear-interpolation" -> {
                expect(method, 3.0f, new int[]{0, 10}, new float[]{1, 5}, 5);
                expect(method, 5.0f, new int[]{0, 10}, new float[]{1, 5}, 50);
                expect(method, Float.NaN, new int[]{0, 0}, new float[]{1, 5}, 5);
            }
            case "page-alignment" -> {
                expect(method, 24L, 17L, 8, 24L);
                expect(method, -1L, 17L, 8, 23L);
                expect(method, -1L, Long.MAX_VALUE, 2, Long.MAX_VALUE);
            }
            case "bucket-counts" -> {
                var buckets = new short[256];
                buckets[2] = Short.MAX_VALUE;
                expect(method, 3, new byte[]{0, 1, 2, -1}, buckets);
                require(buckets[0] == 1 && buckets[1] == 1 && buckets[2] == Short.MAX_VALUE && buckets[255] == 1,
                    "Incorrect unsigned bin classification or saturation");
            }
            case "monotonic-sequence" -> {
                expect(method, true, new char[]{'a', 'c', 'c', 'b'}, 3);
                expect(method, false, new char[]{'a', 'c', 'c', 'b'}, 4);
                expect(method, false, new char[65], 65);
            }
            case "trimmed-sum" -> {
                expect(method, 12L, new int[]{999, 1, 4, 8, 100}, 1, 4);
                expect(method, 7L, new int[]{7, 7, 7}, 0, 3);
                expect(method, 0L, new int[]{7, 7}, 0, 3);
            }
            case "threshold-crossings" -> {
                expect(method, 2, new double[]{1, 3, Double.NaN, 1, 2, 3, Double.POSITIVE_INFINITY}, 2.0);
                expect(method, 0, new double[]{1, 3}, Double.NaN);
            }
            default -> throw new AssertionError("Missing behavior fixture for " + utility);
        }
    }

    private static void expect(Method method, Object expected, Object... args) throws Exception {
        Object actual = method.invoke(null, args);
        require(expected.equals(actual), method.getName() + " returned " + actual + ", expected " + expected);
    }

    private static ClassNode read(byte[] bytes) {
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static byte[] reference(String owner, int version, int access) {
        var writer = new ClassWriter(0);
        writer.visit(version, access | ACC_SUPER, owner, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void sameBytes(FilePadding.Generation first, FilePadding.Generation second) {
        require(new ArrayList<>(first.classes().keySet()).equals(new ArrayList<>(second.classes().keySet()))
            && first.nativeMethods().equals(second.nativeMethods()), "Generated class or method plan changed");
        first.classes().forEach((name, bytes) -> require(Arrays.equals(bytes, second.classes().get(name)),
            "Generated bytecode changed for " + name));
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }

    private static final class Loader extends ClassLoader {
        Class<?> define(byte[] bytes) {
            return defineClass(null, bytes, 0, bytes.length);
        }
    }
}
