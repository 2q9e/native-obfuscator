package dev.nativeobfuscator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import static org.objectweb.asm.Opcodes.*;

/** Outlines a returning, wrapping integer operation from a bounded subset of translated methods. */
final class AnalysisNative {
    static final int MAX_METHODS = 8;
    private static final int MAX_INSTRUCTIONS = 64;

    private final long seed;
    private final int limit;
    private final List<Selection> selected = new ArrayList<>();

    AnalysisNative(long seed, int limit) {
        if (limit < 0 || limit > MAX_METHODS)
            throw new IllegalArgumentException("Native helper method limit must be between 0 and " + MAX_METHODS);
        this.seed = seed;
        this.limit = limit;
    }

    record Selection(String root, String reason, int instructionIndex, int opcode,
                     String rootSection, String helperSection, String helper, boolean reversed) {
        private static Selection skipped(String root, String reason) {
            return new Selection(root, reason, -1, -1, "", "", "", false);
        }

        boolean enabled() {
            return instructionIndex >= 0;
        }

        String rootAttribute() {
            return enabled() ? "__attribute__((section(\"" + rootSection + "\"))) " : "";
        }

        String support() {
            if (!enabled()) return "";
            String type = switch (opcode) {
                case LADD, LSUB, LMUL, LAND, LOR, LXOR -> "uint64_t";
                default -> "uint32_t";
            };
            return "static __attribute__((noinline, section(\"" + helperSection + "\"))) " + type + " "
                + helper + "(" + type + " a, " + type + " b) {\n"
                + "    return " + (reversed ? "b" : "a") + " " + operator(opcode)
                + " " + (reversed ? "a" : "b") + ";\n}\n\n";
        }

        String lower(int index, int operation, String left, String right) {
            if (!enabled() || index != instructionIndex || operation != opcode) return null;
            return helper + "(" + (reversed ? right + ", " + left : left + ", " + right) + ")";
        }
    }

    record Evidence(String root, String status, String reason, Long rootBytes, Long helperBytes, Long edgeOffset) {}

    Selection prepare(MethodNode method, String root, String signature) {
        if (method.instructions.size() > 12_000)
            return Selection.skipped(root, "classfile instruction list exceeds the translator limit");
        int returnOpcode = switch (Type.getReturnType(method.desc).getSort()) {
            case Type.INT -> IRETURN;
            case Type.LONG -> LRETURN;
            default -> -1;
        };
        if (returnOpcode < 0) return Selection.skipped(root, "requires an int or long return");
        AbstractInsnNode terminal = previousInstruction(method.instructions.getLast());
        if (terminal == null || terminal.getOpcode() != returnOpcode)
            return Selection.skipped(root, "requires a terminal integer return");
        AbstractInsnNode operation = previousInstruction(terminal.getPrevious());
        if (operation == null || operator(operation.getOpcode()) == null)
            return Selection.skipped(root, "return value is not produced directly by a wrapping binary operation");
        if (!method.tryCatchBlocks.isEmpty())
            return Selection.skipped(root, "exception handlers");

        int argumentStart = (method.access & ACC_STATIC) != 0 ? 0 : 1;
        int argumentEnd = argumentStart;
        for (Type type : Type.getArgumentTypes(method.desc)) argumentEnd += type.getSize();

        int depth = 0;
        int visited = 1;
        boolean readsArgument = false;
        // This subset leaves a single expression on the stack, with no unused inputs or side effects.
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != terminal; insn = insn.getNext()) {
            int opcode = insn.getOpcode();
            if (opcode < 0) continue;
            if (++visited > MAX_INSTRUCTIONS)
                return Selection.skipped(root, "integer expression exceeds " + MAX_INSTRUCTIONS + " instructions");
            int inputs;
            switch (opcode) {
                case ILOAD, LLOAD -> {
                    int slot = ((VarInsnNode) insn).var;
                    if (slot < argumentStart || slot >= argumentEnd)
                        return Selection.skipped(root, "integer load is outside the argument slots");
                    readsArgument = true;
                    inputs = 0;
                }
                case ICONST_M1, ICONST_0, ICONST_1, ICONST_2, ICONST_3, ICONST_4, ICONST_5,
                     LCONST_0, LCONST_1, BIPUSH, SIPUSH -> inputs = 0;
                case LDC -> {
                    Object constant = ((LdcInsnNode) insn).cst;
                    if (!(constant instanceof Integer) && !(constant instanceof Long))
                        return Selection.skipped(root, "expression uses a non-integer constant");
                    inputs = 0;
                }
                case INEG, LNEG, I2L, L2I, I2B, I2C, I2S -> inputs = 1;
                case IADD, ISUB, IMUL, IAND, IOR, IXOR, LADD, LSUB, LMUL, LAND, LOR, LXOR,
                     ISHL, ISHR, IUSHR, LSHL, LSHR, LUSHR -> inputs = 2;
                default -> {
                    return Selection.skipped(root, "requires a straight-line integer expression over arguments and constants");
                }
            }
            if (depth < inputs) return Selection.skipped(root, "integer expression has an invalid stack shape");
            depth += 1 - inputs;
        }
        if (depth != 1) return Selection.skipped(root, "integer expression does not produce a single stack value");
        if (!readsArgument) return Selection.skipped(root, "integer expression does not read an argument");
        if (selected.size() >= limit) return Selection.skipped(root, "native helper method limit reached");

        int methodHash = signature.hashCode();
        int ordinal = selected.size();
        String suffix = Integer.toHexString(0x1000 | (methodHash & 0xff) << 4 | ordinal).substring(1);
        boolean reversed = new SplittableRandom(seed ^ 0x5694a91d56c7ef02L ^ methodHash).nextBoolean();
        return new Selection(root, "", method.instructions.indexOf(operation), operation.getOpcode(),
            ".nar" + suffix, ".nah" + suffix, "native_analysis_" + suffix, reversed);
    }

    void accept(Selection site) {
        if (site.enabled()) selected.add(site);
    }

    int selectedCount() {
        return selected.size();
    }

    List<Evidence> verify(Path library, NativeTarget target, String objdump) throws InterruptedException {
        return AnalysisNativeEvidence.verify(library, target, objdump, selected);
    }

    static String objdump(String clang) {
        return AnalysisNativeEvidence.objdump(clang);
    }

    private static AbstractInsnNode previousInstruction(AbstractInsnNode instruction) {
        while (instruction != null && instruction.getOpcode() < 0) instruction = instruction.getPrevious();
        return instruction;
    }

    private static String operator(int opcode) {
        return switch (opcode) {
            case IADD, LADD -> "+";
            case ISUB, LSUB -> "-";
            case IMUL, LMUL -> "*";
            case IAND, LAND -> "&";
            case IOR, LOR -> "|";
            case IXOR, LXOR -> "^";
            default -> null;
        };
    }
}
