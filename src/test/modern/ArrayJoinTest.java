import com.google.gson.JsonParser;
import dev.nativeobfuscator.translate.MethodCompiler;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;

import static org.objectweb.asm.Opcodes.*;

/** Compile with the installed distribution's libraries on the classpath, then run
 *  with a working directory argument followed by any native compiler flags. */
public final class ArrayJoinTest {
    private static final String NATIVE_METHODS = "abcdefghi";
    private static final Map<String, String> RETAINED = Map.of("j", "constant requires JVM linkage");

    private static MethodVisitor start(ClassWriter writer, String name, String descriptor) {
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, name, descriptor, null, null);
        method.visitCode();
        return method;
    }

    private static void finish(MethodVisitor method, int opcode) {
        method.visitInsn(opcode);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void chooseArray(MethodVisitor method) {
        Label right = new Label(), joined = new Label();
        method.visitVarInsn(ILOAD, 0);
        method.visitJumpInsn(IFEQ, right);
        method.visitVarInsn(ALOAD, 1);
        method.visitJumpInsn(GOTO, joined);
        method.visitLabel(right);
        method.visitVarInsn(ALOAD, 2);
        method.visitLabel(joined);
    }

    private static byte[] inputClass() {
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override protected String getCommonSuperClass(String first, String second) {
                if ((first.equals("q/b") && second.equals("q/c")) || (first.equals("q/c") && second.equals("q/b")))
                    return "java/lang/Object";
                return super.getCommonSuperClass(first, second);
            }
        };
        writer.visit(V21, ACC_PUBLIC | ACC_FINAL, "q/a", null, "java/lang/Object", null);

        MethodVisitor method = start(writer, "a", "(Z[Lq/b;[Lq/c;)I");
        chooseArray(method);
        method.visitInsn(ARRAYLENGTH);
        finish(method, IRETURN);

        method = start(writer, "b", "(Z[[I[[JILjava/lang/Object;)I");
        chooseArray(method);
        method.visitVarInsn(ILOAD, 3);
        method.visitInsn(AALOAD);
        method.visitVarInsn(ALOAD, 4);
        Label different = new Label();
        method.visitJumpInsn(IF_ACMPNE, different);
        method.visitInsn(ICONST_1);
        method.visitInsn(IRETURN);
        method.visitLabel(different);
        method.visitInsn(ICONST_0);
        finish(method, IRETURN);

        method = start(writer, "c", "(Z[Ljava/lang/String;[Ljava/lang/Integer;ILjava/lang/Object;)V");
        chooseArray(method);
        method.visitVarInsn(ILOAD, 3);
        method.visitVarInsn(ALOAD, 4);
        method.visitInsn(AASTORE);
        finish(method, RETURN);

        method = start(writer, "d", "([II)I");
        method.visitVarInsn(ALOAD, 0);
        method.visitInsn(ICONST_0);
        method.visitVarInsn(ILOAD, 1);
        method.visitInsn(IASTORE);
        method.visitVarInsn(ALOAD, 0);
        method.visitInsn(ICONST_0);
        method.visitInsn(IALOAD);
        finish(method, IRETURN);

        method = start(writer, "e", "(I)I");
        method.visitInsn(ACONST_NULL);
        method.visitVarInsn(ILOAD, 0);
        method.visitInsn(BALOAD);
        finish(method, IRETURN);

        method = start(writer, "f", "(Ljava/lang/Object;)V");
        method.visitInsn(ACONST_NULL);
        method.visitInsn(ICONST_0);
        method.visitVarInsn(ALOAD, 0);
        method.visitInsn(AASTORE);
        finish(method, RETURN);

        method = start(writer, "g", "(Z[[[I[[[JI)I");
        chooseArray(method);
        method.visitVarInsn(ILOAD, 3);
        method.visitInsn(AALOAD);
        method.visitInsn(ARRAYLENGTH);
        finish(method, IRETURN);

        method = start(writer, "h", "(Z[I[JI)I");
        chooseArray(method);
        method.visitTypeInsn(CHECKCAST, "[I");
        method.visitVarInsn(ILOAD, 3);
        method.visitInsn(IALOAD);
        finish(method, IRETURN);

        method = start(writer, "i", "([Ljava/lang/String;I)I");
        Label loop = new Label(), end = new Label();
        method.visitLabel(loop);
        method.visitVarInsn(ILOAD, 1);
        method.visitJumpInsn(IFLT, end);
        method.visitVarInsn(ALOAD, 0);
        method.visitVarInsn(ILOAD, 1);
        method.visitInsn(AALOAD);
        method.visitInsn(POP);
        method.visitIincInsn(1, -1);
        method.visitJumpInsn(GOTO, loop);
        method.visitLabel(end);
        method.visitInsn(ICONST_0);
        finish(method, IRETURN);

        method = start(writer, "j", "()I");
        var bootstrap = new Handle(H_INVOKESTATIC, "java/lang/invoke/ConstantBootstraps", "explicitCast",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;Ljava/lang/Object;)Ljava/lang/Object;", false);
        method.visitLdcInsn(new ConstantDynamic("x", "I", bootstrap, 41));
        finish(method, IRETURN);

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void checkMixedPrimitiveMerge(byte[] bytes) throws Exception {
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        var method = node.methods.stream().filter(m -> m.name.equals("h")).findFirst().orElseThrow();
        var cast = Arrays.stream(method.instructions.toArray())
            .filter(insn -> insn.getOpcode() == CHECKCAST).findFirst().orElseThrow();
        method.instructions.remove(cast);
        try {
            MethodCompiler.compile(node, method, "mixed_primitives");
        } catch (MethodCompiler.Unsupported expected) {
            if (!expected.getMessage().contains("First argument: expected [I, but found"))
                throw new AssertionError("Unexpected mixed primitive array diagnostic", expected);
            return;
        }
        throw new AssertionError("Mixed primitive arrays were accepted by IALOAD");
    }

    private static void entry(JarOutputStream jar, String name, byte[] bytes) throws IOException {
        jar.putNextEntry(new JarEntry(name));
        jar.write(bytes);
        jar.closeEntry();
    }

    private static Path fixture(Path work) throws Exception {
        Path original = work.resolve("original.jar");
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, Runner.class.getName());
        try (var jar = new JarOutputStream(Files.newOutputStream(original), manifest)) {
            byte[] bytes = inputClass();
            checkMixedPrimitiveMerge(bytes);
            entry(jar, "q/a.class", bytes);
            for (String name : List.of("q/b", "q/c")) {
                var writer = new ClassWriter(0);
                writer.visit(V21, ACC_PUBLIC | ACC_FINAL, name, null, "java/lang/Object", null);
                writer.visitEnd();
                entry(jar, name + ".class", writer.toByteArray());
            }
            String runner = Runner.class.getName() + ".class";
            try (var stream = Runner.class.getResourceAsStream("/" + runner)) {
                if (stream == null) throw new AssertionError("Missing " + runner);
                entry(jar, runner, stream.readAllBytes());
            }
        }
        return original;
    }

    private record Result(int exit, String output, String errors) {}

    private static Result runJava(Path work, String label, List<String> args) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path output = work.resolve(label + ".out");
        Path errors = work.resolve(label + ".err");
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", executable).toString());
        command.addAll(args);
        int exit = new ProcessBuilder(command)
            .redirectOutput(output.toFile()).redirectError(errors.toFile()).start().waitFor();
        return new Result(exit, Files.readString(output), Files.readString(errors));
    }

    private static void checkExit(Result result, int expectedExit) {
        if (result.exit != expectedExit || result.errors.contains("WARNING in native method")
            || result.output.contains("WARNING in native method"))
            throw new AssertionError(result);
    }

    private static Result runTool(Path work, Path jar, String label, String mode, List<String> options) throws Exception {
        var args = new ArrayList<>(List.of("-cp", System.getProperty("java.class.path"), "dev.nativeobfuscator.Main",
            mode, "--input", jar.toString(), "--include", "q/a#*"));
        args.addAll(options);
        return runJava(work, label, args);
    }

    private static void checkReport(Result result, String supported, String unsupported) {
        var pending = new HashMap<>(RETAINED);
        var accepted = new StringBuilder(NATIVE_METHODS);
        for (String line : result.output.lines().toList()) {
            var decision = JsonParser.parseString(line).getAsJsonObject();
            String signature = decision.get("method").getAsString();
            String name = signature.substring(signature.indexOf('#') + 1, signature.indexOf('('));
            String status = decision.get("result").getAsString();
            if (status.equals(supported) && accepted.indexOf(name) >= 0) {
                accepted.deleteCharAt(accepted.indexOf(name));
            } else if (status.equals(unsupported) && pending.containsKey(name)
                && decision.get("reason").getAsString().endsWith(pending.get(name))) {
                pending.remove(name);
            } else {
                throw new AssertionError("Unexpected decision " + line);
            }
        }
        if (!accepted.isEmpty() || !pending.isEmpty()) throw new AssertionError("Missing decisions in " + result.output);
    }

    public static void main(String[] args) throws Exception {
        Path work = Files.createDirectories(Path.of(args[0])).toAbsolutePath();
        Path original = fixture(work);
        Path protectedJar = work.resolve("protected.jar");
        List<String> jvmFlags = List.of("-Xverify:all", "-Xcheck:jni", "--enable-native-access=ALL-UNNAMED", "-jar");
        var baseline = runJava(work, "original", append(jvmFlags, original.toString(), "java"));
        checkExit(baseline, 0);

        var analysis = runTool(work, original, "analysis", "analyze", List.of("--unsupported", "retain"));
        checkExit(analysis, 0);
        checkReport(analysis, "eligible", "retained");

        var strict = runTool(work, original, "strict", "analyze", List.of());
        checkExit(strict, 1);
        checkReport(strict, "eligible", "rejected");

        var options = new ArrayList<>(List.of("--output", protectedJar.toString(), "--unsupported", "retain", "--padding", "off"));
        options.addAll(Arrays.asList(args).subList(1, args.length));
        var protection = runTool(work, original, "protection", "protect", options);
        checkExit(protection, 0);
        checkReport(protection, "selected", "retained");

        var nativeRun = runJava(work, "protected", append(jvmFlags, protectedJar.toString(), NATIVE_METHODS));
        checkExit(nativeRun, 0);
        if (!baseline.output.equals(nativeRun.output)) throw new AssertionError("Differential mismatch: " + work);
        System.out.println("Array join fixture matched " + baseline.output.lines().count() + " outcomes across "
            + NATIVE_METHODS.length() + " native methods and " + RETAINED.size() + " retained methods");
    }

    private static List<String> append(List<String> prefix, String... tail) {
        var args = new ArrayList<>(prefix);
        args.addAll(List.of(tail));
        return args;
    }

    public static final class Runner {
        private static void outcome(Method method, Object... args) throws ReflectiveOperationException {
            try {
                System.out.println(method.invoke(null, args));
            } catch (InvocationTargetException e) {
                System.out.println(e.getCause().getClass().getName());
            }
        }

        public static void main(String[] args) throws Exception {
            var methods = new HashMap<String, Method>();
            for (Method method : Class.forName("q.a").getDeclaredMethods()) {
                boolean expectedNative = !args[0].equals("java") && args[0].contains(method.getName());
                if (Modifier.isNative(method.getModifiers()) != expectedNative)
                    throw new AssertionError("Unexpected native state for " + method);
                methods.put(method.getName(), method);
            }
            Object left = Array.newInstance(Class.forName("q.b"), 2);
            Object right = Array.newInstance(Class.forName("q.c"), 3);
            outcome(methods.get("a"), true, left, right);
            outcome(methods.get("a"), false, left, right);
            outcome(methods.get("a"), true, null, right);

            int[][] ints = {{5}, null};
            long[][] longs = {{7, 9}};
            outcome(methods.get("b"), true, ints, longs, 0, ints[0]);
            outcome(methods.get("b"), true, ints, longs, 1, null);
            outcome(methods.get("b"), false, ints, longs, 0, longs[0]);
            outcome(methods.get("b"), false, ints, longs, 0, new long[]{7, 9});
            outcome(methods.get("b"), false, ints, longs, 1, longs[0]);

            String[] strings = {"before"};
            Integer[] numbers = {0};
            outcome(methods.get("c"), true, strings, numbers, 0, "after");
            outcome(methods.get("c"), false, strings, numbers, 0, -42);
            outcome(methods.get("c"), true, strings, numbers, 0, 42);
            outcome(methods.get("c"), false, strings, numbers, 1, "wrong type");
            outcome(methods.get("c"), true, null, numbers, 1, 42);
            System.out.println(strings[0] + "/" + numbers[0]);

            int[] value = {3};
            outcome(methods.get("d"), value, Integer.MIN_VALUE);
            System.out.println(value[0]);
            outcome(methods.get("d"), new int[0], 0);
            outcome(methods.get("e"), Integer.MIN_VALUE);
            outcome(methods.get("f"), "value");

            outcome(methods.get("g"), true, new int[][][]{ints}, new long[][][]{longs}, 0);
            outcome(methods.get("g"), false, new int[][][]{ints}, new long[][][]{longs}, 0);
            outcome(methods.get("h"), true, ints[0], longs[0], 0);
            outcome(methods.get("h"), false, ints[0], longs[0], 0);
            outcome(methods.get("i"), strings, 0);
            String[] repeated = new String[8192];
            Arrays.fill(repeated, "element");
            repeated[0] = null;
            outcome(methods.get("i"), repeated, repeated.length - 1);
            outcome(methods.get("i"), repeated, repeated.length);
            outcome(methods.get("i"), null, 0);
            outcome(methods.get("j"));
        }
    }
}
