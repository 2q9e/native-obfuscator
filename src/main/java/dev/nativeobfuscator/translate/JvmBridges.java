package dev.nativeobfuscator.translate;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.BasicInterpreter;

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

    static boolean externalCall(ClassNode owner, MethodInsnNode call) {
        if (call.owner.equals(owner.name) || call.name.startsWith("<")) return false;
        return switch (call.getOpcode()) {
            case INVOKESTATIC, INVOKEVIRTUAL, INVOKEINTERFACE -> true;
            default -> false;
        };
    }

    static boolean requiresBridge(ClassNode owner, AbstractInsnNode instruction) {
        return (instruction instanceof MethodInsnNode call && externalCall(owner, call))
            || (instruction instanceof FieldInsnNode field && !field.owner.equals(owner.name));
    }

    List<MethodNode> methods() { return methods; }

    MethodNode call(MethodInsnNode call, int instructionIndex, Type receiver) {
        var operation = new MethodInsnNode(call.getOpcode(), call.owner, call.name, call.desc, call.itf);
        return create(operation, instructionIndex, receiver, Type.getReturnType(call.desc), Type.getArgumentTypes(call.desc));
    }

    MethodNode field(FieldInsnNode field, int instructionIndex, Type receiver, boolean store) {
        Type type = Type.getType(field.desc);
        var operation = new FieldInsnNode(field.getOpcode(), field.owner, field.name, field.desc);
        return store ? create(operation, instructionIndex, receiver, Type.VOID_TYPE, new Type[]{type})
            : create(operation, instructionIndex, receiver, type, new Type[0]);
    }

    private MethodNode create(AbstractInsnNode operation, int instructionIndex, Type receiver,
                              Type returned, Type[] arguments) {
        boolean nullReceiver = BasicInterpreter.NULL_TYPE.equals(receiver);
        Type[] parameters = arguments;
        if (receiver != null) {
            parameters = new Type[arguments.length + 1];
            parameters[0] = nullReceiver ? Type.getType(Object.class) : receiver;
            System.arraycopy(arguments, 0, parameters, 1, arguments.length);
        }
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
        for (int i = 0; i < parameters.length; i++) {
            Type parameter = parameters[i];
            // Retain the verifier's null type for an operation with a literal null receiver.
            bridge.instructions.add(nullReceiver && i == 0
                ? new InsnNode(ACONST_NULL) : new VarInsnNode(parameter.getOpcode(ILOAD), slots));
            slots += parameter.getSize();
        }
        bridge.instructions.add(operation);
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
