package dev.nativeobfuscator;

import dev.nativeobfuscator.runtime.AnalysisManifest;
import dev.nativeobfuscator.runtime.NativeRuntime;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import static org.objectweb.asm.Opcodes.*;

final class AnalysisRuntime {
    private static final String RUNTIME = Type.getInternalName(NativeRuntime.class);
    private static final String MANIFEST = Type.getInternalName(AnalysisManifest.class);
    private static final String FIELD = "analysisDigest";
    private static final String ORIGINAL_REGISTER = "register0";
    static final String REGISTER = "registerBoundV1";
    private static final String BOUND_DESCRIPTOR = "(Ljava/lang/Class;I[B[B[B)V";

    private AnalysisRuntime() {}

    static String manifestClass(String loader) {
        return loader + "Manifest";
    }

    static byte[] loader(String loader) throws IOException {
        var node = new ClassNode();
        readClass(NativeRuntime.class, RUNTIME, node);
        var mappings = new SimpleRemapper(ASM9, Map.of(RUNTIME, loader, MANIFEST, manifestClass(loader)));
        node.fields.add(new FieldNode(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, FIELD, "[B", null, null));

        for (var method : node.methods) {
            if (method.name.equals("<clinit>")) {
                var verify = new InsnList();
                verify.add(new LdcInsnNode(Type.getObjectType(RUNTIME)));
                verify.add(new MethodInsnNode(INVOKESTATIC, RUNTIME, "resourceRoot", "()Ljava/lang/String;", false));
                verify.add(new MethodInsnNode(INVOKESTATIC, MANIFEST, "verifyRuntime",
                    "(Ljava/lang/Class;Ljava/lang/String;)[B", false));
                verify.add(new FieldInsnNode(PUTSTATIC, RUNTIME, FIELD, "[B"));
                method.instructions.insert(verify);
            } else if (method.name.equals("register")) {
                for (var instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call
                            && call.owner.equals(RUNTIME) && call.name.equals(ORIGINAL_REGISTER)) {
                        method.instructions.insertBefore(call, new FieldInsnNode(GETSTATIC, RUNTIME, FIELD, "[B"));
                        call.name = REGISTER;
                        call.desc = BOUND_DESCRIPTOR;
                    }
                }
            } else if (method.name.equals(ORIGINAL_REGISTER)) {
                method.name = REGISTER;
                method.desc = BOUND_DESCRIPTOR;
            }
        }
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(new ClassRemapper(writer, mappings));
        return writer.toByteArray();
    }

    static byte[] manifest(String loader) throws IOException {
        var node = new ClassNode();
        readClass(AnalysisManifest.class, MANIFEST, node);
        var writer = new ClassWriter(0);
        node.accept(new ClassRemapper(writer, new SimpleRemapper(ASM9, Map.of(MANIFEST, manifestClass(loader)))));
        return writer.toByteArray();
    }

    private static void readClass(Class<?> template, String name, ClassNode node) throws IOException {
        try (InputStream input = template.getResourceAsStream("/" + name + ".class")) {
            if (input == null) throw new IOException("Tool JAR is missing runtime template " + name);
            new ClassReader(input).accept(node, 0);
        }
    }
}
