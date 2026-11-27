import java.nio.file.Files;
import java.nio.file.Path;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

public final class GenerateCondy {
    private static final String OWNER = "modern/CondyFixture";
    private static final String MARKER = "modern/Marker_\uD801\uDC00";
    private static final String BOOTSTRAP = "(Ljava/lang/invoke/MethodHandles$Lookup;"
            + "Ljava/lang/String;Ljava/lang/Class;I)I";

    public static void main(String[] args) throws Exception {
        int version = switch (args[0]) {
            case "21" -> V21;
            case "25" -> V25;
            default -> throw new IllegalArgumentException("Expected classfile release 21 or 25");
        };
        Path classes = Path.of(args[1]);
        Files.createDirectories(classes.resolve("modern"));
        Files.write(classes.resolve(OWNER + ".class"), fixture(version));
        Files.write(classes.resolve(MARKER + ".class"), marker(version));
    }

    private static byte[] fixture(int version) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        writer.visit(version, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, OWNER, null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "bootstrapCalls", "I", null, null).visitEnd();

        MethodVisitor bootstrap = writer.visitMethod(ACC_PRIVATE | ACC_STATIC, "bootstrap", BOOTSTRAP, null, null);
        bootstrap.visitCode();
        bootstrap.visitFieldInsn(GETSTATIC, OWNER, "bootstrapCalls", "I");
        bootstrap.visitInsn(ICONST_1);
        bootstrap.visitInsn(IADD);
        bootstrap.visitFieldInsn(PUTSTATIC, OWNER, "bootstrapCalls", "I");
        bootstrap.visitVarInsn(ILOAD, 3);
        bootstrap.visitInsn(ICONST_2);
        bootstrap.visitInsn(IADD);
        bootstrap.visitInsn(IRETURN);
        finish(bootstrap);

        MethodVisitor condy = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "calcCondy", "()I", null, null);
        condy.visitCode();
        var handle = new Handle(H_INVOKESTATIC, OWNER, "bootstrap", BOOTSTRAP, false);
        condy.visitLdcInsn(new ConstantDynamic("answer", "I", handle, 40));
        condy.visitInsn(IRETURN);
        finish(condy);

        var nullBootstrap = new Handle(H_INVOKESTATIC, "java/lang/invoke/ConstantBootstraps", "nullConstant",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;", false);
        for (String descriptor : new String[]{"Ljava/lang/String;", "[I"}) {
            String name = descriptor.startsWith("[") ? "calcNullArray" : "calcNull";
            MethodVisitor loadNull = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, name, "()" + descriptor, null, null);
            loadNull.visitCode();
            loadNull.visitLdcInsn(new ConstantDynamic("nullValue", descriptor, nullBootstrap));
            loadNull.visitInsn(ARETURN);
            finish(loadNull);
        }

        MethodVisitor missing = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "calcMissingNull",
            "(Z)Ljava/lang/Object;", null, null);
        missing.visitCode();
        var skip = new Label();
        missing.visitVarInsn(ILOAD, 0);
        missing.visitJumpInsn(IFEQ, skip);
        missing.visitLdcInsn(new ConstantDynamic("missing", "Lmodern/MissingNullType;", nullBootstrap));
        missing.visitInsn(ARETURN);
        missing.visitLabel(skip);
        missing.visitInsn(ACONST_NULL);
        missing.visitInsn(ARETURN);
        finish(missing);

        MethodVisitor badNull = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "calcPrimitiveNull", "()I", null, null);
        badNull.visitCode();
        badNull.visitLdcInsn(new ConstantDynamic("invalidNull", "I", nullBootstrap));
        badNull.visitInsn(IRETURN);
        finish(badNull);

        MethodVisitor nativeCandidate = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "calc\u03bb\uD801\uDC00",
                "(IJL" + MARKER + ";)J", null, null);
        nativeCandidate.visitCode();
        nativeCandidate.visitVarInsn(ILOAD, 0);
        nativeCandidate.visitInsn(I2L);
        nativeCandidate.visitVarInsn(LLOAD, 1);
        nativeCandidate.visitInsn(LXOR);
        nativeCandidate.visitVarInsn(ALOAD, 3);
        var result = new Label();
        nativeCandidate.visitJumpInsn(IFNONNULL, result);
        nativeCandidate.visitInsn(LCONST_1);
        nativeCandidate.visitInsn(LADD);
        nativeCandidate.visitLabel(result);
        nativeCandidate.visitInsn(LRETURN);
        finish(nativeCandidate);

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] marker(int version) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        writer.visit(version, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, MARKER, null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        finish(constructor);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void finish(MethodVisitor method) {
        method.visitMaxs(0, 0);
        method.visitEnd();
    }
}
