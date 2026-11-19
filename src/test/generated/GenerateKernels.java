import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import static org.objectweb.asm.Opcodes.*;

public final class GenerateKernels {
    public static void main(String[] args) throws Exception {
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Integer.parseInt(args[1]) + 44, ACC_PUBLIC | ACC_FINAL | ACC_SUPER,
            "GeneratedKernels", null, "java/lang/Object", null);
        var random = new Random(Long.parseLong(args[2]));
        for (int i = 0; i < 60; i++) {
            var method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "calc" + i, "(JJI)J", null, null);
            method.visitCode();
            method.visitVarInsn(LLOAD, 0);
            method.visitVarInsn(LLOAD, 2);
            method.visitInsn(DUP2_X2);
            method.visitInsn(LSUB);
            method.visitInsn(LADD);
            for (int step = 0; step < 26; step++) {
                switch (random.nextInt(8)) {
                    case 0 -> {
                        method.visitInsn(DUP2);
                        method.visitInsn(LMUL);
                    }
                    case 1 -> {
                        method.visitVarInsn(ILOAD, 4);
                        method.visitInsn(DUP_X2);
                        method.visitInsn(LSHL);
                        method.visitInsn(DUP2_X1);
                        method.visitInsn(POP2);
                        method.visitInsn(LUSHR);
                    }
                    case 2 -> {
                        method.visitLdcInsn(random.nextLong());
                        method.visitInsn(LXOR);
                    }
                    case 3 -> {
                        method.visitVarInsn(ILOAD, 4);
                        method.visitLdcInsn(random.nextInt());
                        method.visitInsn(DUP_X1);
                        method.visitInsn(ISUB);
                        method.visitInsn(IXOR);
                        method.visitInsn(LSHR);
                    }
                    case 4 -> {
                        method.visitVarInsn(LLOAD, 2);
                        method.visitInsn(random.nextBoolean() ? LADD : LSUB);
                    }
                    case 5 -> {
                        var negative = new org.objectweb.asm.Label();
                        var exit = new org.objectweb.asm.Label();
                        method.visitVarInsn(ILOAD, 4);
                        method.visitJumpInsn(IFLT, negative);
                        method.visitVarInsn(LLOAD, 0);
                        method.visitInsn(LADD);
                        method.visitJumpInsn(GOTO, exit);
                        method.visitLabel(negative);
                        method.visitInsn(LNEG);
                        method.visitLabel(exit);
                    }
                    case 6 -> {
                        method.visitVarInsn(ILOAD, 4);
                        method.visitLdcInsn(random.nextInt());
                        method.visitInsn(DUP2_X2);
                        method.visitInsn(IAND);
                        method.visitInsn(LSHL);
                        method.visitInsn(DUP2_X2);
                        method.visitInsn(POP2);
                        method.visitInsn(IOR);
                        method.visitInsn(LSHR);
                    }
                    case 7 -> {
                        method.visitInsn(L2I);
                        method.visitVarInsn(ILOAD, 4);
                        method.visitLdcInsn(random.nextInt());
                        method.visitLdcInsn(random.nextInt());
                        method.visitInsn(DUP2_X2);
                        for (int j = 0; j < 5; j++) method.visitInsn(IADD);
                        method.visitInsn(I2L);
                    }
                }
            }
            method.visitInsn(LRETURN);
            method.visitMaxs(0, 0);
            method.visitEnd();
        }
        writer.visitEnd();
        Files.write(Path.of(args[0], "GeneratedKernels.class"), writer.toByteArray());
    }
}
