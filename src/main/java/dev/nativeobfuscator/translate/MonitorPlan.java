package dev.nativeobfuscator.translate;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

import static org.objectweb.asm.Opcodes.*;

/** Proves one structured explicit monitor at a time, with an immutable lock local while held. */
final class MonitorPlan {
    private static final int UNSEEN = -2;
    private static final int UNLOCKED = -1;

    private MonitorPlan() {}

    static boolean verify(MethodNode method, Frame<BasicValue>[] frames, ExceptionHandlers handlers)
            throws MethodCompiler.Unsupported {
        var code = method.instructions.toArray();
        if (Arrays.stream(code).noneMatch(i -> i.getOpcode() == MONITORENTER || i.getOpcode() == MONITOREXIT))
            return false;
        var targets = new HashSet<LabelNode>();
        for (var handler : method.tryCatchBlocks) {
            targets.add(handler.start);
            targets.add(handler.end);
            targets.add(handler.handler);
        }
        for (var instruction : code) {
            if (instruction instanceof JumpInsnNode jump) targets.add(jump.label);
            if (instruction instanceof TableSwitchInsnNode table) {
                targets.add(table.dflt);
                targets.addAll(table.labels);
            }
            if (instruction instanceof LookupSwitchInsnNode lookup) {
                targets.add(lookup.dflt);
                targets.addAll(lookup.labels);
            }
        }
        int[] states = new int[code.length];
        Arrays.fill(states, UNSEEN);
        var pending = new ArrayDeque<Integer>();
        enqueue(0, UNLOCKED, states, pending);
        while (!pending.isEmpty()) {
            int index = pending.removeFirst();
            int held = states[index];
            var instruction = code[index];
            int op = instruction.getOpcode();
            int after = held;
            if (held >= 0 && (frames[index] == null || !frames[index].getLocal(held).isReference()))
                throw unsupported("lock local is not live while held");
            if (held >= 0 && (instruction instanceof VarInsnNode var && var.getOpcode() >= ISTORE
                && var.getOpcode() <= ASTORE && (var.var == held
                    || (var.getOpcode() == LSTORE || var.getOpcode() == DSTORE) && var.var + 1 == held)
                || instruction instanceof IincInsnNode increment && increment.var == held))
                throw unsupported("lock local is overwritten while held");
            if (op == MONITORENTER) {
                var previous = previousInstruction(instruction, targets);
                if (held != UNLOCKED || !(previous instanceof VarInsnNode store) || store.getOpcode() != ASTORE
                    || previousInstruction(store, targets).getOpcode() != DUP)
                    throw unsupported("expected a single monitor captured by DUP and ASTORE");
                after = store.var;
            } else if (op == MONITOREXIT) {
                if (!(previousInstruction(instruction, targets) instanceof VarInsnNode load)
                    || load.getOpcode() != ALOAD || load.var != held)
                    throw unsupported("monitor exit does not load the held lock local");
                after = UNLOCKED;
            }
            if (op >= IRETURN && op <= RETURN && held != UNLOCKED)
                throw unsupported("method can return with a monitor held");
            if (canThrow(op)) {
                boolean catchAll = false;
                for (var handler : handlers.covering(index)) {
                    enqueue(method.instructions.indexOf(handler.handler), held, states, pending);
                    if (handler.type == null || handler.type.equals("java/lang/Throwable")) {
                        catchAll = true;
                        break;
                    }
                }
                if (held != UNLOCKED && !catchAll && op != MONITOREXIT)
                    throw unsupported("exception can leave the synchronized block without releasing its lock");
            }
            if (instruction instanceof JumpInsnNode jump) {
                enqueue(method.instructions.indexOf(jump.label), after, states, pending);
            } else if (instruction instanceof TableSwitchInsnNode table) {
                enqueue(method.instructions.indexOf(table.dflt), after, states, pending);
                for (var target : table.labels) enqueue(method.instructions.indexOf(target), after, states, pending);
                continue;
            } else if (instruction instanceof LookupSwitchInsnNode lookup) {
                enqueue(method.instructions.indexOf(lookup.dflt), after, states, pending);
                for (var target : lookup.labels) enqueue(method.instructions.indexOf(target), after, states, pending);
                continue;
            }
            if (op != ATHROW && op != GOTO && !(op >= IRETURN && op <= RETURN))
                enqueue(index + 1, after, states, pending);
        }
        for (int i = 0; i < code.length; i++) {
            if (frames[i] != null && states[i] == UNSEEN
                && (code[i].getOpcode() == MONITORENTER || code[i].getOpcode() == MONITOREXIT))
                throw unsupported("monitor in an unproven exception path");
        }
        return true;
    }

    private static AbstractInsnNode previousInstruction(AbstractInsnNode instruction, HashSet<LabelNode> targets)
            throws MethodCompiler.Unsupported {
        var previous = instruction.getPrevious();
        while (previous != null && previous.getOpcode() < 0) {
            if (previous instanceof FrameNode || previous instanceof LabelNode label && targets.contains(label))
                throw unsupported("lock capture or release crosses a control-flow boundary");
            previous = previous.getPrevious();
        }
        if (previous == null) throw unsupported("lock capture or release has no operand producer");
        return previous;
    }

    private static boolean canThrow(int opcode) {
        return opcode >= 0 && switch (opcode) {
            case NOP, ACONST_NULL, ICONST_M1, ICONST_0, ICONST_1, ICONST_2, ICONST_3, ICONST_4, ICONST_5,
                 LCONST_0, LCONST_1, FCONST_0, FCONST_1, FCONST_2, DCONST_0, DCONST_1, BIPUSH, SIPUSH,
                 ILOAD, LLOAD, FLOAD, DLOAD, ALOAD, ISTORE, LSTORE, FSTORE, DSTORE, ASTORE, IINC,
                 DUP, POP, POP2, SWAP, GOTO, IFEQ, IFNE, IFNULL, IFNONNULL,
                 TABLESWITCH, LOOKUPSWITCH, IRETURN, LRETURN, FRETURN, DRETURN, ARETURN, RETURN -> false;
            default -> true;
        };
    }

    private static void enqueue(int index, int state, int[] states, ArrayDeque<Integer> pending)
            throws MethodCompiler.Unsupported {
        if (index < 0 || index >= states.length) throw unsupported("invalid control-flow edge");
        if (states[index] == UNSEEN) {
            states[index] = state;
            pending.add(index);
        } else if (states[index] != state) {
            throw unsupported("control-flow paths disagree on monitor ownership");
        }
    }

    private static MethodCompiler.Unsupported unsupported(String reason) {
        return new MethodCompiler.Unsupported("explicit monitor ownership is not proven: " + reason);
    }
}
