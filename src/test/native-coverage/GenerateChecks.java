import dev.nativeobfuscator.translate.MethodCompiler;
import java.nio.file.Files;
import java.nio.file.Path;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
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
    private static void exceptionFixtures(ClassWriter writer) {
        var order = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "covCatchTableOrder", "(I)I", null, null);
        var start = new Label();
        var end = new Label();
        var first = new Label();
        var second = new Label();
        order.visitCode();
        order.visitTryCatchBlock(start, end, first, "java/lang/RuntimeException");
        order.visitTryCatchBlock(start, end, second, "java/lang/ArithmeticException");
        order.visitLabel(start);
        order.visitIntInsn(BIPUSH, 45);
        order.visitVarInsn(ILOAD, 0);
        order.visitInsn(IDIV);
        order.visitLabel(end);
        order.visitInsn(IRETURN);
        order.visitLabel(first);
        order.visitInsn(POP);
        order.visitInsn(ICONST_1);
        order.visitInsn(IRETURN);
        order.visitLabel(second);
        order.visitInsn(POP);
        order.visitInsn(ICONST_2);
        order.visitInsn(IRETURN);
        order.visitMaxs(0, 0);
        order.visitEnd();

        var capacity = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "covCatchCapacity", "(I)I", null, null);
        capacity.visitCode();
        capacity.visitInsn(ICONST_0);
        capacity.visitVarInsn(ISTORE, 1);
        for (int i = 0; i < 64; i++) {
            var begin = new Label();
            var finish = new Label();
            var catchTarget = new Label();
            var next = new Label();
            capacity.visitTryCatchBlock(begin, finish, catchTarget, "java/lang/ArithmeticException");
            capacity.visitLabel(begin);
            capacity.visitInsn(ICONST_1);
            capacity.visitVarInsn(ILOAD, 0);
            capacity.visitInsn(IDIV);
            capacity.visitInsn(POP);
            capacity.visitLabel(finish);
            capacity.visitJumpInsn(GOTO, next);
            capacity.visitLabel(catchTarget);
            capacity.visitVarInsn(ASTORE, 2);
            capacity.visitVarInsn(ALOAD, 2);
            capacity.visitInsn(POP);
            capacity.visitIincInsn(1, 1);
            capacity.visitLabel(next);
        }
        capacity.visitVarInsn(ILOAD, 1);
        capacity.visitInsn(IRETURN);
        capacity.visitMaxs(0, 0);
        capacity.visitEnd();

        var backward = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "rejectBackwardHandler", "(I)I", null, null);
        var entry = new Label();
        var exit = new Label();
        var handler = new Label();
        backward.visitCode();
        backward.visitTryCatchBlock(entry, exit, handler, "java/lang/ArithmeticException");
        backward.visitJumpInsn(GOTO, entry);
        backward.visitLabel(handler);
        backward.visitInsn(POP);
        backward.visitInsn(ICONST_M1);
        backward.visitInsn(IRETURN);
        backward.visitLabel(entry);
        backward.visitInsn(ICONST_1);
        backward.visitVarInsn(ILOAD, 0);
        backward.visitInsn(IDIV);
        backward.visitLabel(exit);
        backward.visitInsn(IRETURN);
        backward.visitMaxs(0, 0);
        backward.visitEnd();
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
        reject(owner, method("wrongObjectReturn", "(Ljava/lang/Object;)Ljava/lang/String;",
            new VarInsnNode(ALOAD, 0), new InsnNode(ARETURN)));
        reject(owner, method("wrongArrayReturn", "([I)[J", new VarInsnNode(ALOAD, 0), new InsnNode(ARETURN)));
        reject(owner, method("wrongThrow", "(Ljava/lang/Object;)V", new VarInsnNode(ALOAD, 0), new InsnNode(ATHROW)));
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V21, ACC_PUBLIC | ACC_FINAL, "RawOps", null, "java/lang/Object", new String[]{"java/lang/Cloneable"});
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
        var nullReceiver = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "covExternalNull", "()I", null, null);
        nullReceiver.visitCode();
        nullReceiver.visitInsn(ACONST_NULL);
        nullReceiver.visitMethodInsn(INVOKEVIRTUAL, "RawStaticTarget", "missing", "()I", false);
        nullReceiver.visitInsn(IRETURN);
        nullReceiver.visitMaxs(0, 0);
        nullReceiver.visitEnd();
        var protectedCall = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "covExternalProtected", "(LRawOps;)I", null, null);
        protectedCall.visitCode();
        protectedCall.visitVarInsn(ALOAD, 0);
        protectedCall.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "clone", "()Ljava/lang/Object;", false);
        protectedCall.visitInsn(POP);
        protectedCall.visitInsn(ICONST_1);
        protectedCall.visitInsn(IRETURN);
        protectedCall.visitMaxs(0, 0);
        protectedCall.visitEnd();
        var missingField = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "covExternalMissingField", "()I", null, null);
        missingField.visitCode();
        missingField.visitInsn(ACONST_NULL);
        missingField.visitFieldInsn(GETFIELD, "RawStaticTarget", "missing", "I");
        missingField.visitInsn(IRETURN);
        missingField.visitMaxs(0, 0);
        missingField.visitEnd();
        var missingCast = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "covCastMissing", "(Ljava/lang/Object;)I", null, null);
        missingCast.visitCode();
        missingCast.visitVarInsn(ALOAD, 0);
        missingCast.visitTypeInsn(CHECKCAST, "MissingCastTarget");
        missingCast.visitInsn(POP);
        missingCast.visitInsn(ICONST_1);
        missingCast.visitInsn(IRETURN);
        missingCast.visitMaxs(0, 0);
        missingCast.visitEnd();
        var missingArray = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "covAllocateMissingArray", "(I)I", null, null);
        missingArray.visitCode();
        missingArray.visitVarInsn(ILOAD, 0);
        missingArray.visitTypeInsn(ANEWARRAY, "MissingArrayTarget");
        missingArray.visitInsn(ARRAYLENGTH);
        missingArray.visitInsn(IRETURN);
        missingArray.visitMaxs(0, 0);
        missingArray.visitEnd();
        String concatDescriptor = "(Ljava/lang/String;I)Ljava/lang/String;";
        var plainConcat = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "covPlainConcat", concatDescriptor, null, null);
        plainConcat.visitCode();
        plainConcat.visitVarInsn(ALOAD, 0);
        plainConcat.visitVarInsn(ILOAD, 1);
        plainConcat.visitInvokeDynamicInsn("concat", concatDescriptor, new Handle(H_INVOKESTATIC,
            "java/lang/invoke/StringConcatFactory", "makeConcat",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false));
        plainConcat.visitInsn(ARETURN);
        plainConcat.visitMaxs(0, 0);
        plainConcat.visitEnd();
        exceptionFixtures(writer);
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
        var conflicting = new ClassWriter(0);
        conflicting.visit(V21, ACC_PUBLIC, "Coverage$DefaultDispatch", null, "java/lang/Object", null);
        conflicting.visitEnd();
        Files.write(Path.of(args[0]).resolveSibling("conflicting-dispatch.class"), conflicting.toByteArray());
        System.out.println("Malformed operand policies: 11 passed; raw narrowing methods: 4 generated; private invokespecial: 1 generated");
    }
}
