package dev.nativeobfuscator.translate;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import static org.objectweb.asm.Opcodes.*;

/** Keeps symbolic JVM linkage in the original caller class. */
final class JvmBridges {
    private final ClassNode owner;
    private final MethodNode caller;
    private final List<MethodNode> methods = new ArrayList<>();
    private String prefix;

    JvmBridges(ClassNode owner, MethodNode caller) {
        this.owner = owner;
        this.caller = caller;
    }

    static boolean externalStatic(ClassNode owner, MethodInsnNode call) {
        return call.getOpcode() == INVOKESTATIC && !call.owner.equals(owner.name) && !call.name.startsWith("<");
    }

    List<MethodNode> methods() { return methods; }

    MethodNode staticCall(MethodInsnNode call, int instructionIndex) {
        Type returned = Type.getReturnType(call.desc);
        Type[] parameters = Type.getArgumentTypes(call.desc);
        // Preserve the entire JVM int-stack value before executing the original instruction.
        for (int i = 0; i < parameters.length; i++) {
            int sort = parameters[i].getSort();
            if (sort >= Type.BOOLEAN && sort <= Type.SHORT) parameters[i] = Type.INT_TYPE;
        }
        String descriptor = Type.getMethodDescriptor(returned, parameters);
        if (prefix == null) prefix = "$$native$link$" + owner.methods.indexOf(caller) + "$";
        String name = prefix + instructionIndex;
        while (hasMethod(name, descriptor)) name += "$";

        var bridge = new MethodNode(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, name, descriptor, null, null);
        int slots = 0;
        for (Type parameter : parameters) {
            bridge.instructions.add(new VarInsnNode(parameter.getOpcode(ILOAD), slots));
            slots += parameter.getSize();
        }
        bridge.instructions.add(new MethodInsnNode(INVOKESTATIC, call.owner, call.name, call.desc, call.itf));
        bridge.instructions.add(new InsnNode(returned.getOpcode(IRETURN)));
        bridge.maxLocals = slots;
        bridge.maxStack = Math.max(slots, returned.getSize());
        methods.add(bridge);
        return bridge;
    }

    private boolean hasMethod(String name, String descriptor) {
        return owner.methods.stream().anyMatch(method -> method.name.equals(name) && method.desc.equals(descriptor));
    }
}
