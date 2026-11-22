import dev.nativeobfuscator.translate.MethodCompiler;
import java.nio.file.Files;
import java.nio.file.Path;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.*;
import static org.objectweb.asm.Opcodes.*;

/** Generates legal raw narrowing bytecode and tests malformed operand rejection. */
public final class GenerateChecks {
    private static void reject(ClassNode owner, MethodNode method) throws Exception {
        try { MethodCompiler.compile(owner, method, "invalid"); }
        catch (MethodCompiler.Unsupported expected) {
            if (expected.getMessage().isEmpty()) throw new AssertionError("Missing diagnostic");
            return;
        }
        throw new AssertionError("Malformed operand shape accepted: " + method.name);
    }
    private static MethodNode method(String name, String descriptor, AbstractInsnNode... instructions) {
        var method = new MethodNode(ACC_PUBLIC | ACC_STATIC, name, descriptor, null, null);
        for (var instruction : instructions) method.instructions.add(instruction);
        method.maxStack = 8;
        method.maxLocals = 8;
        return method;
    }
    public static void main(String[] args) throws Exception {
        var owner = new ClassNode();
        owner.version = V21;
        owner.access = ACC_PUBLIC;
        owner.name = "Malformed";
        owner.superName = "java/lang/Object";
        owner.fields.add(new FieldNode(ACC_PRIVATE, "count", "I", null, null));
        owner.fields.add(new FieldNode(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "constant", "I", null, 1));
        MethodNode instanceTarget = method("target", "()I", new InsnNode(ICONST_0), new InsnNode(IRETURN));
        instanceTarget.access = ACC_PUBLIC;
        owner.methods.add(instanceTarget);
        reject(owner, method("wrongArrayLoad", "([B)I", new VarInsnNode(ALOAD, 0), new InsnNode(ICONST_0), new InsnNode(IALOAD), new InsnNode(IRETURN)));
        reject(owner, method("wrongArrayStore", "([I)V", new VarInsnNode(ALOAD, 0), new InsnNode(ICONST_0), new InsnNode(ACONST_NULL), new InsnNode(IASTORE), new InsnNode(RETURN)));
        reject(owner, method("wrongWideDup", "()J", new InsnNode(LCONST_0), new InsnNode(DUP), new InsnNode(LRETURN)));
        reject(owner, method("wrongReceiver", "()I", new InsnNode(ICONST_0), new FieldInsnNode(GETFIELD, owner.name, "count", "I"), new InsnNode(IRETURN)));
        reject(owner, method("wrongStaticField", "()I", new FieldInsnNode(GETSTATIC, owner.name, "count", "I"), new InsnNode(IRETURN)));
        reject(owner, method("finalWrite", "()V", new InsnNode(ICONST_0), new FieldInsnNode(PUTSTATIC, owner.name, "constant", "I"), new InsnNode(RETURN)));
        reject(owner, method("wrongStaticCall", "()I", new MethodInsnNode(INVOKESTATIC, owner.name, "target", "()I", false), new InsnNode(IRETURN)));
        reject(owner, method("wrongArrayLength", "(Ljava/lang/Object;)I", new VarInsnNode(ALOAD, 0), new InsnNode(ARRAYLENGTH), new InsnNode(IRETURN)));
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V21, ACC_PUBLIC | ACC_FINAL, "RawOps", null, "java/lang/Object", null);
        for (int i = 0; i < 4; i++) {
            char arrayType = "ZBCS".charAt(i);
            int store = new int[]{BASTORE, BASTORE, CASTORE, SASTORE}[i];
            int load = new int[]{BALOAD, BALOAD, CALOAD, SALOAD}[i];
            MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "covRaw" + arrayType, "([" + arrayType + "II)I", null, null);
            method.visitCode();
            method.visitVarInsn(ALOAD, 0);
            method.visitVarInsn(ILOAD, 1);
            method.visitVarInsn(ILOAD, 2);
            method.visitInsn(store);
            method.visitVarInsn(ALOAD, 0);
            method.visitVarInsn(ILOAD, 1);
            method.visitInsn(load);
            method.visitInsn(IRETURN);
            method.visitMaxs(0, 0);
            method.visitEnd();
        }
        MethodVisitor capacity = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "covReferenceCapacity", "([Ljava/lang/Object;)I", null, null);
        capacity.visitCode();
        for (int i = 0; i < 64; i++) {
            capacity.visitVarInsn(ALOAD, 0);
            capacity.visitInsn(ICONST_0);
            capacity.visitInsn(AALOAD);
            capacity.visitInsn(POP);
        }
        capacity.visitVarInsn(ALOAD, 0);
        capacity.visitInsn(ARRAYLENGTH);
        capacity.visitInsn(IRETURN);
        capacity.visitMaxs(0, 0);
        capacity.visitEnd();
        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        MethodVisitor local = writer.visitMethod(ACC_PRIVATE, "local", "(I)I", null, null);
        local.visitCode();
        local.visitVarInsn(ILOAD, 1);
        local.visitInsn(INEG);
        local.visitInsn(IRETURN);
        local.visitMaxs(0, 0);
        local.visitEnd();
        MethodVisitor special = writer.visitMethod(ACC_PUBLIC, "covSpecial", "(I)I", null, null);
        special.visitCode();
        special.visitVarInsn(ALOAD, 0);
        special.visitVarInsn(ILOAD, 1);
        special.visitMethodInsn(INVOKESPECIAL, "RawOps", "local", "(I)I", false);
        special.visitInsn(IRETURN);
        special.visitMaxs(0, 0);
        special.visitEnd();
        var byteAsInt = writer.visitMethod(ACC_PRIVATE | ACC_STATIC, "byteAsInt", "(B)I", null, null);
        byteAsInt.visitCode();
        byteAsInt.visitVarInsn(ILOAD, 0);
        byteAsInt.visitInsn(IRETURN);
        byteAsInt.visitMaxs(0, 0);
        byteAsInt.visitEnd();
        var rawCall = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "rejectRawCall", "(I)I", null, null);
        rawCall.visitCode();
        rawCall.visitVarInsn(ILOAD, 0);
        rawCall.visitMethodInsn(INVOKESTATIC, "RawOps", "byteAsInt", "(B)I", false);
        rawCall.visitInsn(IRETURN);
        rawCall.visitMaxs(0, 0);
        rawCall.visitEnd();
        var external = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "covLinkageRaw", "(I)I", null, null);
        external.visitCode();
        external.visitVarInsn(ILOAD, 0);
        external.visitMethodInsn(INVOKESTATIC, "RawStaticTarget", "byteAsInt", "(B)I", false);
        external.visitInsn(IRETURN);
        external.visitMaxs(0, 0);
        external.visitEnd();
        var missing = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "covLinkageMissing", "()I", null, null);
        missing.visitCode();
        missing.visitMethodInsn(INVOKESTATIC, "RawStaticTarget", "missing", "()I", false);
        missing.visitInsn(IRETURN);
        missing.visitMaxs(0, 0);
        missing.visitEnd();
        writer.visitEnd();
        Files.write(Path.of(args[0]).resolve("RawOps.class"), writer.toByteArray());

        var peer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        peer.visit(V21, ACC_FINAL, "RawStaticTarget", null, "java/lang/Object", null);
        var peerTarget = peer.visitMethod(ACC_STATIC, "byteAsInt", "(B)I", null, null);
        peerTarget.visitCode();
        peerTarget.visitVarInsn(ILOAD, 0);
        peerTarget.visitInsn(IRETURN);
        peerTarget.visitMaxs(0, 0);
        peerTarget.visitEnd();
        peer.visitEnd();
        Files.write(Path.of(args[0]).resolve("RawStaticTarget.class"), peer.toByteArray());
        System.out.println("Malformed operand policies: 8 passed; raw narrowing methods: 4 generated; private invokespecial: 1 generated");
    }
}
