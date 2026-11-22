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
            || (instruction instanceof FieldInsnNode field && !field.owner.equals(owner.name))
            || instruction.getOpcode() == CHECKCAST || instruction.getOpcode() == INSTANCEOF
            || instruction.getOpcode() == NEW || arrayAllocation(instruction.getOpcode());
    }

    static boolean arrayAllocation(int opcode) {
        return opcode == NEWARRAY || opcode == ANEWARRAY || opcode == MULTIANEWARRAY;
    }

    List<MethodNode> methods() { return methods; }

    MethodNode call(MethodInsnNode call, int instructionIndex, Type receiver) {
        var operation = new MethodInsnNode(call.getOpcode(), call.owner, call.name, call.desc, call.itf);
        return create(instructionIndex, receiver, Type.getReturnType(call.desc), Type.getArgumentTypes(call.desc), operation);
    }

    MethodNode field(FieldInsnNode field, int instructionIndex, Type receiver, boolean store) {
        Type type = Type.getType(field.desc);
        var operation = new FieldInsnNode(field.getOpcode(), field.owner, field.name, field.desc);
        return store ? create(instructionIndex, receiver, Type.VOID_TYPE, new Type[]{type}, operation)
            : create(instructionIndex, receiver, type, new Type[0], operation);
    }

    MethodNode typeCheck(TypeInsnNode check, int instructionIndex) {
        var operation = new TypeInsnNode(check.getOpcode(), check.desc);
        Type[] arguments = {Type.getType(Object.class)};
        // A successful cast keeps the existing JNI handle, including inside a loop.
        return check.getOpcode() == CHECKCAST
            ? create(instructionIndex, null, Type.VOID_TYPE, arguments, operation, new InsnNode(POP))
            : create(instructionIndex, null, Type.INT_TYPE, arguments, operation);
    }

    MethodNode allocateArray(AbstractInsnNode allocation, int instructionIndex, Type type) {
        int dimensions = allocation instanceof MultiANewArrayInsnNode multi ? multi.dims : 1;
        Type[] arguments = new Type[dimensions];
        java.util.Arrays.fill(arguments, Type.INT_TYPE);
        return create(instructionIndex, null, type, arguments, allocation.clone(null));
    }

    static MethodInsnNode constructor(TypeInsnNode allocation) throws MethodCompiler.Unsupported {
        String reason = "object allocation requires NEW, DUP, plain argument loads, and its matching constructor";
        AbstractInsnNode duplicate = allocation.getNext();
        if (duplicate == null || duplicate.getOpcode() != DUP) throw new MethodCompiler.Unsupported(reason);
        AbstractInsnNode current = duplicate.getNext();
        int arguments = 0;
        // Moving NEW across these pushes cannot reorder a call, heap read, or constant resolution.
        while (plainArgument(current)) {
            arguments++;
            current = current.getNext();
        }
        if (!(current instanceof MethodInsnNode call) || call.getOpcode() != INVOKESPECIAL || call.itf
            || !call.name.equals("<init>") || !call.owner.equals(allocation.desc)
            || arguments != Type.getArgumentCount(call.desc) || Type.getReturnType(call.desc) != Type.VOID_TYPE)
            throw new MethodCompiler.Unsupported(reason);
        return call;
    }

    private static boolean plainArgument(AbstractInsnNode instruction) {
        if (instruction == null) return false;
        int opcode = instruction.getOpcode();
        return (opcode >= ILOAD && opcode <= ALOAD) || (opcode >= ACONST_NULL && opcode <= SIPUSH)
            || (instruction instanceof LdcInsnNode constant && (constant.cst instanceof Integer
                || constant.cst instanceof Long || constant.cst instanceof Float || constant.cst instanceof Double));
    }

    MethodNode construct(TypeInsnNode allocation, MethodInsnNode constructor, int instructionIndex) {
        var operation = new MethodInsnNode(INVOKESPECIAL, constructor.owner, constructor.name, constructor.desc, false);
        MethodNode bridge = create(instructionIndex, null, Type.getObjectType(allocation.desc),
            Type.getArgumentTypes(constructor.desc), operation);
        var prefix = new InsnList();
        prefix.add(new TypeInsnNode(NEW, allocation.desc));
        prefix.add(new InsnNode(DUP));
        bridge.instructions.insert(prefix);
        bridge.maxStack = bridge.maxLocals + 2;
        return bridge;
    }

    private MethodNode create(int instructionIndex, Type receiver, Type returned, Type[] arguments,
                              AbstractInsnNode... operations) {
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
        for (AbstractInsnNode operation : operations) bridge.instructions.add(operation);
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
