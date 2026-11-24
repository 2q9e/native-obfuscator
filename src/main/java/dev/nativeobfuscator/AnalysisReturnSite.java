package dev.nativeobfuscator;

import java.util.ArrayDeque;
import java.util.HashSet;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import static org.objectweb.asm.Opcodes.*;

/** Finds one wrapping operation that contributes to every reachable normal return. */
record AnalysisReturnSite(AbstractInsnNode operation, boolean leftRuntime, boolean rightRuntime) {
    private static final AbstractInsnNode PARAMETER = new LabelNode();

    static AnalysisReturnSite find(MethodNode method, int limit) {
        int kind = Type.getReturnType(method.desc).getSort();
        if ((kind != Type.INT && kind != Type.LONG) || !method.tryCatchBlocks.isEmpty()
            || method.instructions.size() > limit * 4 || method.maxLocals > limit || method.maxStack > limit)
            return null;
        int instructions = 0;
        for (var instruction : method.instructions) {
            int opcode = instruction.getOpcode();
            // Linked verification follows direct branches. Switches can lower to indirect jumps.
            if (opcode == TABLESWITCH || opcode == LOOKUPSWITCH) return null;
            if (opcode >= 0 && ++instructions > limit) return null;
        }
        Frame<SourceValue>[] frames;
        try {
            frames = new Analyzer<>(new Origins()).analyze("java/lang/Object", method);
        } catch (AnalyzerException | RuntimeException invalid) {
            return null;
        }
        AbstractInsnNode producer = null;
        for (var instruction : method.instructions) {
            if (instruction.getOpcode() != IRETURN && instruction.getOpcode() != LRETURN) continue;
            var frame = frame(method, frames, instruction);
            if (frame == null) continue;
            var source = onlySource(frame.getStack(frame.getStackSize() - 1));
            if (source == null || (producer != null && source != producer)) return null;
            producer = source;
        }
        if (producer instanceof MethodInsnNode call) {
            var frame = frame(method, frames, call);
            int first = frame.getStackSize() - Type.getArgumentCount(call.desc);
            for (int i = frame.getStackSize() - 1; i >= first; i--) {
                var site = candidate(method, frames, onlySource(frame.getStack(i)));
                if (site != null) return site;
            }
            return null;
        }
        return candidate(method, frames, producer);
    }

    private static AnalysisReturnSite candidate(MethodNode method, Frame<SourceValue>[] frames,
                                                AbstractInsnNode operation) {
        if (operation == null || AnalysisNative.operator(operation.getOpcode()) == null) return null;
        var frame = frame(method, frames, operation);
        var left = frame.getStack(frame.getStackSize() - 2);
        var right = frame.getStack(frame.getStackSize() - 1);
        if (AnalysisNative.trivial(operation.getOpcode(), literal(left), literal(right))) return null;
        boolean leftRuntime = runtimeInput(method, frames, left);
        boolean rightRuntime = runtimeInput(method, frames, right);
        return leftRuntime || rightRuntime ? new AnalysisReturnSite(operation, leftRuntime, rightRuntime) : null;
    }

    private static boolean runtimeInput(MethodNode method, Frame<SourceValue>[] frames, SourceValue value) {
        var pending = new ArrayDeque<>(value.insns);
        var seen = new HashSet<AbstractInsnNode>();
        while (!pending.isEmpty()) {
            var source = pending.removeFirst();
            if (source == PARAMETER) return true;
            if (!seen.add(source)) continue;
            if (source.getOpcode() == GETFIELD || source.getOpcode() == GETSTATIC || source.getOpcode() == ARRAYLENGTH
                || (source.getOpcode() >= IALOAD && source.getOpcode() <= SALOAD)
                || (source.getOpcode() >= INVOKEVIRTUAL && source.getOpcode() <= INVOKEDYNAMIC)) return true;
            var frame = frame(method, frames, source);
            int depth = frame.getStackSize();
            switch (source.getOpcode()) {
                case IINC -> pending.addAll(frame.getLocal(((IincInsnNode) source).var).insns);
                case INEG, LNEG, I2L, L2I, I2B, I2C, I2S -> pending.addAll(frame.getStack(depth - 1).insns);
                case IADD, LADD, ISUB, LSUB, IMUL, LMUL, IAND, LAND, IOR, LOR, IXOR, LXOR,
                     ISHL, LSHL, ISHR, LSHR, IUSHR, LUSHR -> {
                    pending.addAll(frame.getStack(depth - 2).insns);
                    pending.addAll(frame.getStack(depth - 1).insns);
                }
                default -> { }
            }
        }
        return false;
    }

    private static Frame<SourceValue> frame(MethodNode method, Frame<SourceValue>[] frames, AbstractInsnNode instruction) {
        return frames[method.instructions.indexOf(instruction)];
    }

    private static Long literal(SourceValue value) {
        var source = onlySource(value);
        return source == null ? null : AnalysisNative.literal(source);
    }

    private static AbstractInsnNode onlySource(SourceValue value) {
        return value.insns.size() == 1 ? value.insns.iterator().next() : null;
    }

    private static final class Origins extends SourceInterpreter {
        Origins() { super(ASM9); }

        @Override public SourceValue newParameterValue(boolean instance, int local, Type type) {
            return new SourceValue(type.getSize(), PARAMETER);
        }

        @Override public SourceValue copyOperation(AbstractInsnNode instruction, SourceValue value) {
            return value;
        }
    }
}
