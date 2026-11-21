package dev.nativeobfuscator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.SplittableRandom;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import static org.objectweb.asm.Opcodes.*;

/** Outlines a returning, wrapping integer operation into a bounded chain of native helpers. */
final class AnalysisNative {
    static final int MAX_METHODS = 8;
    static final int MAX_HELPERS_PER_ROOT = 3;
    private static final int MAX_INSTRUCTIONS = 64;

    private final long seed;
    private final int methodLimit;
    private final int helpersPerRoot;
    private final List<Selection> selected = new ArrayList<>();

    AnalysisNative(long seed, int methodLimit) {
        this(seed, methodLimit, 1);
    }

    AnalysisNative(long seed, int methodLimit, int helpersPerRoot) {
        if (methodLimit < 0 || methodLimit > MAX_METHODS)
            throw new IllegalArgumentException("Native helper method limit must be between 0 and " + MAX_METHODS);
        if (helpersPerRoot < 0 || helpersPerRoot > MAX_HELPERS_PER_ROOT)
            throw new IllegalArgumentException("Native helpers per root must be between 0 and " + MAX_HELPERS_PER_ROOT);
        this.seed = seed;
        this.methodLimit = methodLimit;
        this.helpersPerRoot = helpersPerRoot;
    }

    record Helper(String section, String name, long mask) {}

    record Selection(String root, String reason, int instructionIndex, int opcode, String rootSection,
                     List<Helper> helpers, boolean reversed, boolean partitionLeft) {
        Selection {
            helpers = List.copyOf(helpers);
        }

        private static Selection skipped(String root, String reason) {
            return new Selection(root, reason, -1, -1, "", List.of(), false, false);
        }

        boolean enabled() {
            return instructionIndex >= 0;
        }

        int helperCount() {
            return helpers.size();
        }

        String helperSection() {
            return enabled() ? helpers.getFirst().section() : "";
        }

        String helper() {
            return enabled() ? helpers.getFirst().name() : "";
        }

        String rootAttribute() {
            return enabled() ? "__attribute__((section(\"" + rootSection + "\"))) " : "";
        }

        String support() {
            if (!enabled()) return "";
            boolean longOperation = wide(opcode);
            boolean split = helpers.size() > 1;
            String type = longOperation ? "uint64_t" : "uint32_t";
            String left = reversed ? "b" : "a";
            String right = reversed ? "a" : "b";
            var code = new StringBuilder();
            for (int i = helpers.size() - 1; i >= 0; i--) {
                Helper part = helpers.get(i);
                // Hidden linkage keeps the arithmetic generic during frontend optimization.
                code.append("__attribute__((noinline, visibility(\"hidden\"), section(\"").append(part.section())
                    .append("\"))) ").append(type).append(" ").append(part.name())
                    .append("(").append(type).append(" a, ").append(type).append(" b) {\n");
                if (split) {
                    String mask = longOperation ? "UINT64_C(0x" + HexFormat.of().toHexDigits(part.mask()) + ")"
                        : "UINT32_C(0x" + HexFormat.of().toHexDigits((int) part.mask()) + ")";
                    code.append("    ").append(type).append(" part = ")
                        .append(partitionLeft ? left : right).append(" & ").append(mask).append(";\n");
                }
                String leftPart = split && partitionLeft ? "part" : left;
                String rightPart = split && !partitionLeft ? "part" : right;
                if (i == helpers.size() - 1) {
                    code.append("    return ").append(leftPart).append(" ").append(operator(opcode))
                        .append(" ").append(rightPart).append(";\n");
                } else {
                    code.append("    ").append(type).append(" prior = ").append(helpers.get(i + 1).name())
                        .append("(a, b);\n")
                        .append("    return ").append(combine(opcode, partitionLeft, leftPart, rightPart))
                        .append(";\n");
                }
                code.append("}\n\n");
            }
            return code.toString();
        }

        String lower(int index, int operation, String left, String right) {
            if (!enabled() || index != instructionIndex || operation != opcode) return null;
            return helper() + "(" + (reversed ? right + ", " + left : left + ", " + right) + ")";
        }
    }

    record Evidence(String root, String status, String reason, Long rootBytes, Long helperBytes, Long edgeOffset,
                    List<EdgeEvidence> edges) {
        Evidence {
            edges = List.copyOf(edges);
        }
    }

    record EdgeEvidence(String sourceSection, String targetSection, String status, String reason, Long sourceBytes,
                        Long targetBytes, Long edgeOffset) {}

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

        var readsArgument = new boolean[MAX_INSTRUCTIONS];
        var literals = new Long[MAX_INSTRUCTIONS];
        int depth = 0;
        int visited = 1;
        boolean leftReadsArgument = false;
        boolean rightReadsArgument = false;
        // This subset leaves a single expression on the stack, with no unused inputs or side effects.
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != terminal; insn = insn.getNext()) {
            int opcode = insn.getOpcode();
            if (opcode < 0) continue;
            if (++visited > MAX_INSTRUCTIONS)
                return Selection.skipped(root, "integer expression exceeds " + MAX_INSTRUCTIONS + " instructions");
            int inputs;
            boolean reads = false;
            switch (opcode) {
                case ILOAD, LLOAD -> {
                    int slot = ((VarInsnNode) insn).var;
                    if (slot < argumentStart || slot >= argumentEnd)
                        return Selection.skipped(root, "integer load is outside the argument slots");
                    reads = true;
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
            if (insn == operation) {
                leftReadsArgument = readsArgument[depth - 2];
                rightReadsArgument = readsArgument[depth - 1];
                if ((leftReadsArgument || rightReadsArgument) && trivial(opcode, literals[depth - 2], literals[depth - 1]))
                    return Selection.skipped(root, "terminal operation has an identity or annihilating literal operand");
            }
            for (int i = depth - inputs; i < depth; i++) reads |= readsArgument[i];
            depth -= inputs;
            literals[depth] = literal(insn);
            readsArgument[depth++] = reads;
        }
        if (depth != 1) return Selection.skipped(root, "integer expression does not produce a single stack value");
        if (!readsArgument[0]) return Selection.skipped(root, "integer expression does not read an argument");
        if (selected.size() >= methodLimit) return Selection.skipped(root, "native helper method limit reached");
        if (helpersPerRoot == 0) return Selection.skipped(root, "native helpers per root limit reached");

        int methodHash = signature.hashCode();
        int ordinal = selected.size();
        String suffix = Integer.toHexString(0x1000 | (methodHash & 0xff) << 4 | ordinal).substring(1);
        var random = new SplittableRandom(seed ^ 0x5694a91d56c7ef02L ^ methodHash);
        boolean reversed = random.nextBoolean();
        boolean partitionLeft = leftReadsArgument && (!rightReadsArgument || random.nextBoolean());
        int opcode = operation.getOpcode();
        List<Long> masks = partition(wide(opcode) ? 64 : 32, helpersPerRoot, random);
        var helpers = new ArrayList<Helper>(helpersPerRoot);
        for (int i = 0; i < helpersPerRoot; i++) {
            String helperSuffix = i == 0 ? suffix : suffix + i;
            helpers.add(new Helper(".nah" + helperSuffix, "native_analysis_" + helperSuffix, masks.get(i)));
        }
        return new Selection(root, "", method.instructions.indexOf(operation), opcode, ".nar" + suffix,
            helpers, reversed, partitionLeft);
    }

    void accept(Selection site) {
        if (site.enabled()) selected.add(site);
    }

    int selectedCount() {
        return selected.size();
    }

    int helperCount() {
        return selected.stream().mapToInt(Selection::helperCount).sum();
    }

    List<Evidence> unavailable(String reason) {
        return AnalysisNativeEvidence.unavailable(selected, reason);
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

    private static Long literal(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        return switch (opcode) {
            case ICONST_M1, ICONST_0, ICONST_1, ICONST_2, ICONST_3, ICONST_4, ICONST_5 -> (long) opcode - ICONST_0;
            case LCONST_0, LCONST_1 -> (long) opcode - LCONST_0;
            case BIPUSH, SIPUSH -> (long) ((IntInsnNode) insn).operand;
            case LDC -> ((Number) ((LdcInsnNode) insn).cst).longValue();
            default -> null;
        };
    }

    private static boolean trivial(int opcode, Long left, Long right) {
        boolean zeroLeft = literalEquals(left, 0);
        boolean zeroRight = literalEquals(right, 0);
        return switch (opcode) {
            case IADD, LADD, IXOR, LXOR -> zeroLeft || zeroRight;
            case ISUB, LSUB -> zeroRight;
            case IMUL, LMUL -> zeroLeft || zeroRight || literalEquals(left, 1) || literalEquals(right, 1);
            case IAND, LAND, IOR, LOR -> zeroLeft || zeroRight || literalEquals(left, -1) || literalEquals(right, -1);
            default -> false;
        };
    }

    private static boolean literalEquals(Long literal, long value) {
        return literal != null && literal == value;
    }

    private static boolean wide(int opcode) {
        return switch (opcode) {
            case LADD, LSUB, LMUL, LAND, LOR, LXOR -> true;
            default -> false;
        };
    }

    private static List<Long> partition(int width, int count, SplittableRandom random) {
        var masks = new ArrayList<Long>(count);
        long used = 0;
        int radius = width / (2 * count);
        for (int i = 1; i < count; i++) {
            int midpoint = i * width / count;
            int boundary = random.nextInt(midpoint - radius + 1, midpoint + radius);
            long lower = (1L << boundary) - 1;
            masks.add(lower ^ used);
            used = lower;
        }
        masks.add((-1L >>> (Long.SIZE - width)) ^ used);
        for (int i = count - 1; i > 0; i--) Collections.swap(masks, i, random.nextInt(i + 1));
        return masks;
    }

    // The masked parts are disjoint and cover the entire operand. Addition and multiplication
    // compose modulo the Java word size, while bitwise operations compose over the same parts.
    private static String combine(int opcode, boolean partitionLeft, String left, String right) {
        return switch (opcode) {
            case IADD, LADD -> "prior + part";
            case ISUB, LSUB -> partitionLeft ? "prior + part" : "prior - part";
            case IMUL, LMUL -> "prior + " + left + " * " + right;
            case IAND, LAND -> "prior | (" + left + " & " + right + ")";
            case IOR, LOR -> "prior | part";
            case IXOR, LXOR -> "prior ^ part";
            default -> throw new IllegalArgumentException("Not a wrapping binary operation");
        };
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
