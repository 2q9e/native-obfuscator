package dev.nativeobfuscator.translate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

/** Plans local-reference reclamation at targets that can be revisited. */
final class ReferenceLocals {
    private static final int STACK_SLOT = 0x8000;
    private static final int CLEANUP_SLOT_LIMIT = 65_536;
    private static final ReferenceLocals EMPTY = new ReferenceLocals(Map.of(), 0);

    private final Map<LabelNode, List<Integer>> cleanup;
    private final int liveCapacity;

    private ReferenceLocals(Map<LabelNode, List<Integer>> cleanup, int liveCapacity) {
        this.cleanup = cleanup;
        this.liveCapacity = liveCapacity;
    }

    static ReferenceLocals plan(MethodNode method, Frame<BasicValue>[] frames, boolean producesReferences)
            throws MethodCompiler.Unsupported {
        if (!producesReferences) return EMPTY;
        var targets = backwardTargets(method);
        if (targets.isEmpty()) return EMPTY;
        var cleanup = new HashMap<LabelNode, List<Integer>>();
        int liveCapacity = 0;
        int cleanupSlots = 0;
        for (LabelNode target : targets) {
            Frame<BasicValue> frame = frames[method.instructions.indexOf(target)];
            if (frame == null) continue;
            var slots = new ArrayList<Integer>();
            for (int i = 0; i < frame.getLocals(); i++) {
                if (frame.getLocal(i).isReference()) slots.add(i);
            }
            for (int i = 0; i < frame.getStackSize(); i++) {
                if (frame.getStack(i).isReference()) slots.add(STACK_SLOT + i);
            }
            cleanupSlots += slots.size();
            if (cleanupSlots > CLEANUP_SLOT_LIMIT)
                throw new MethodCompiler.Unsupported("reference cleanup exceeds the translation slot limit");
            cleanup.put(target, List.copyOf(slots));
            liveCapacity = Math.max(liveCapacity, slots.size());
        }
        return new ReferenceLocals(cleanup, liveCapacity);
    }

    private static Set<LabelNode> backwardTargets(MethodNode method) {
        var targets = new HashSet<LabelNode>();
        for (AbstractInsnNode instruction : method.instructions) {
            int index = method.instructions.indexOf(instruction);
            if (instruction instanceof JumpInsnNode jump) {
                addBackward(method, targets, jump.label, index);
            } else if (instruction instanceof TableSwitchInsnNode table) {
                addBackward(method, targets, table.dflt, index);
                for (LabelNode label : table.labels) addBackward(method, targets, label, index);
            } else if (instruction instanceof LookupSwitchInsnNode lookup) {
                addBackward(method, targets, lookup.dflt, index);
                for (LabelNode label : lookup.labels) addBackward(method, targets, label, index);
            }
        }
        for (TryCatchBlockNode block : method.tryCatchBlocks)
            addBackward(method, targets, block.handler, method.instructions.indexOf(block.end) - 1);
        return targets;
    }

    private static void addBackward(MethodNode method, Set<LabelNode> targets, LabelNode target, int source) {
        int index = method.instructions.indexOf(target);
        if (index >= 0 && index <= source) targets.add(target);
    }

    boolean tracking() { return !cleanup.isEmpty(); }
    int liveCapacity() { return liveCapacity; }
    List<Integer> cleanupSlots(LabelNode label) { return cleanup.get(label); }
}
