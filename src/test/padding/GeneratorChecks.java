package dev.nativeobfuscator;

import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.objectweb.asm.Opcodes.*;

/** Standalone deterministic generator checks; no native compiler is needed. */
public final class GeneratorChecks {
    static final class Loader extends ClassLoader {
        Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); }
    }
    public static void main(String[] args) throws Exception {
        var inputs = new LinkedHashMap<String, byte[]>();
        ClassWriter reference = new ClassWriter(0);
        reference.visit(V1_8, ACC_PUBLIC, "app/Kernel", null, "java/lang/Object", null);
        for (int i = 0; i < 8; i++) {
            var m = reference.visitMethod(ACC_PUBLIC | ACC_STATIC, "value" + i, "(I)I", null, null);
            m.visitCode(); m.visitVarInsn(ILOAD, 0); m.visitInsn(ICONST_1); m.visitInsn(IADD); m.visitInsn(IRETURN); m.visitMaxs(2,1); m.visitEnd();
        }
        reference.visitEnd(); inputs.put("app/Kernel.class", reference.toByteArray());
        for (int count : new int[]{0,1,12,1000}) {
            long started = System.nanoTime();
            var generation = FilePadding.generate(inputs, count, 145);
            var repeated = FilePadding.generate(inputs, count, 145);
            if (generation.classes().size() != count || generation.nativeMethods().size() < count) throw new AssertionError("count");
            if (!generation.classes().keySet().equals(repeated.classes().keySet())) throw new AssertionError("names nondeterministic");
            var families = new HashSet<String>();
            var sizes = new ArrayList<Integer>();
            var signatures = new HashSet<String>();
            var loader = new Loader();
            for (var entry : generation.classes().entrySet()) {
                if (!Arrays.equals(entry.getValue(), repeated.classes().get(entry.getKey()))) throw new AssertionError("bytes nondeterministic");
                var node = new ClassNode(); new ClassReader(entry.getValue()).accept(node, 0);
                if (node.version != V1_8 || !entry.getKey().equals(node.name + ".class")) throw new AssertionError("version/name");
                Class<?> type = loader.define(entry.getValue());
                boolean hasNative = false;
                for (var method : type.getDeclaredMethods()) {
                    signatures.add(org.objectweb.asm.Type.getMethodDescriptor(method));
                    String key = node.name + "#" + method.getName() + org.objectweb.asm.Type.getMethodDescriptor(method);
                    if (generation.nativeMethods().containsKey(key)) { hasNative = true; families.add(generation.nativeMethods().get(key)); }
                    var parameters = method.getParameterTypes(); Object[] values = new Object[parameters.length];
                    for (int i = 0; i < values.length; i++) {
                        Class<?> parameter = parameters[i];
                        if (parameter == int.class) values[i] = i == 2 ? 100 : 4;
                        else if (parameter == long.class) values[i] = 17L;
                        else if (parameter == double.class) values[i] = 3.5;
                        else if (parameter == int[].class) values[i] = new int[]{1,2,3,4,5,6};
                        else if (parameter == byte[].class) values[i] = new byte[]{1,2,3,4,5,6};
                        else if (parameter == long[].class) values[i] = new long[]{1,2,3,4,5,6};
                        else if (parameter == double[].class) values[i] = new double[]{1,2,3,4,5,6};
                        else throw new AssertionError(parameter);
                    }
                    if (java.lang.reflect.Modifier.isStatic(method.getModifiers())) method.invoke(null, values);
                    else { var constructor = type.getConstructor(long.class); Object instance = constructor.newInstance(20L); if (!method.invoke(instance,values).equals(37L)) throw new AssertionError("state"); }
                }
                if (!hasNative) throw new AssertionError("no selected method");
                sizes.add(entry.getValue().length);
            }
            if (count >= 12 && families.size() != 3) throw new AssertionError("families " + families);
            if (count >= 1000 && signatures.size() < 7) throw new AssertionError("signature diversity");
            Collections.sort(sizes);
            System.out.println("count=" + count + " families=" + families + " descriptors=" + signatures.size() + " sourceSizes=" + (sizes.isEmpty() ? "[]" : sizes.getFirst()+"/"+sizes.get(sizes.size()/2)+"/"+sizes.getLast()) + " ms=" + ((System.nanoTime()-started)/1_000_000));
        }
        var first = FilePadding.generate(inputs,1,145);
        var versioned = new LinkedHashMap<>(inputs);
        first.classes().forEach((name, bytes) -> versioned.put("META-INF/versions/17/" + name, bytes));
        if (FilePadding.generate(versioned,1,145).classes().keySet().equals(first.classes().keySet()))
            throw new AssertionError("collision with a versioned owner");
        inputs.putAll(first.classes());
        var collision = FilePadding.generate(inputs,1000,145);
        if (collision.classes().keySet().stream().anyMatch(inputs::containsKey)) throw new AssertionError("collision");
        if (FilePadding.generate(inputs,12,146).classes().keySet().equals(FilePadding.generate(inputs,12,145).classes().keySet())) throw new AssertionError("seed ignored");
        for (int invalid : new int[]{-1,1001,Integer.MAX_VALUE}) {
            try { FilePadding.generate(inputs,invalid,0); throw new AssertionError("invalid count accepted"); } catch (IllegalArgumentException expected) {}
        }
        System.out.println("Generator checks passed");
    }
}
