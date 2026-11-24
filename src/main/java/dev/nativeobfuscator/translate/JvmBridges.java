package dev.nativeobfuscator.translate;

import java.lang.invoke.LambdaMetafactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.BasicInterpreter;

import static org.objectweb.asm.Opcodes.*;

/** Keeps symbolic JVM linkage in the original caller class. */
final class JvmBridges {
    private static final String CALL_SITE_BOOTSTRAP_PARAMETERS =
        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;";
    private static final String CALL_SITE = ")Ljava/lang/invoke/CallSite;";
    private static final String LAMBDA_BOOTSTRAP = CALL_SITE_BOOTSTRAP_PARAMETERS
        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;" + CALL_SITE;
    private static final String ALT_LAMBDA_BOOTSTRAP = CALL_SITE_BOOTSTRAP_PARAMETERS + "[Ljava/lang/Object;" + CALL_SITE;

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
            case INVOKESTATIC, INVOKEVIRTUAL, INVOKEINTERFACE, INVOKESPECIAL -> true;
            default -> false;
        };
    }

    static boolean requiresBridge(ClassNode owner, AbstractInsnNode instruction) {
        return (instruction instanceof MethodInsnNode call && externalCall(owner, call))
            || (instruction instanceof FieldInsnNode field && (!field.owner.equals(owner.name)
                || isVolatile(declaredField(owner, field))))
            || instruction.getOpcode() == CHECKCAST || instruction.getOpcode() == INSTANCEOF
            || instruction.getOpcode() == NEW || instruction.getOpcode() == ATHROW || arrayAllocation(instruction.getOpcode())
            || (instruction instanceof InvokeDynamicInsnNode dynamic && supportsDynamic(dynamic));
    }

    static FieldNode declaredField(ClassNode owner, FieldInsnNode field) {
        return owner.fields.stream().filter(f -> f.name.equals(field.name) && f.desc.equals(field.desc)).findFirst().orElse(null);
    }

    static boolean isVolatile(FieldNode field) {
        return field != null && (field.access & ACC_VOLATILE) != 0;
    }

    static boolean arrayAllocation(int opcode) {
        return opcode == NEWARRAY || opcode == ANEWARRAY || opcode == MULTIANEWARRAY;
    }

    List<MethodNode> methods() { return methods; }

    record BoxedCallback(MethodInsnNode box, MethodInsnNode call, TypeInsnNode cast, MethodInsnNode unbox) {}

    Map<AbstractInsnNode, BoxedCallback> boxedCallbacks() {
        var callbacks = new HashMap<AbstractInsnNode, BoxedCallback>();
        for (AbstractInsnNode instruction : caller.instructions) {
            var callback = boxedCallback(instruction);
            if (callback != null) callbacks.put(instruction, callback);
        }
        return callbacks;
    }

    private BoxedCallback boxedCallback(AbstractInsnNode instruction) {
        if (!(instruction instanceof MethodInsnNode box) || box.getOpcode() != INVOKESTATIC || box.itf
            || !box.name.equals("valueOf") || Type.getArgumentCount(box.desc) != 1
            || !box.owner.equals(wrapper(Type.getArgumentTypes(box.desc)[0]))
            || !Type.getReturnType(box.desc).equals(Type.getObjectType(box.owner))) return null;
        // Match adjacent instructions only. A label can mark a handler boundary or an entry edge.
        if (!(box.getNext() instanceof MethodInsnNode call)
            || (call.getOpcode() != INVOKEVIRTUAL && call.getOpcode() != INVOKEINTERFACE)
            || !externalCall(owner, call) || !call.desc.equals("(Ljava/lang/Object;)Ljava/lang/Object;")
            || !(call.getNext() instanceof TypeInsnNode cast) || cast.getOpcode() != CHECKCAST
            || !(cast.getNext() instanceof MethodInsnNode unbox) || unbox.getOpcode() != INVOKEVIRTUAL || unbox.itf
            || !unbox.owner.equals(cast.desc) || Type.getArgumentCount(unbox.desc) != 0) return null;
        Type result = Type.getReturnType(unbox.desc);
        String wrapper = wrapper(result);
        if (wrapper == null || !unbox.name.equals(result.getClassName() + "Value")) return null;
        boolean number = unbox.owner.equals("java/lang/Number")
            && result.getSort() != Type.BOOLEAN && result.getSort() != Type.CHAR;
        return number || unbox.owner.equals(wrapper) ? new BoxedCallback(box, call, cast, unbox) : null;
    }

    private static String wrapper(Type primitive) {
        return switch (primitive.getSort()) {
            case Type.BOOLEAN -> "java/lang/Boolean";
            case Type.BYTE -> "java/lang/Byte";
            case Type.CHAR -> "java/lang/Character";
            case Type.SHORT -> "java/lang/Short";
            case Type.INT -> "java/lang/Integer";
            case Type.LONG -> "java/lang/Long";
            case Type.FLOAT -> "java/lang/Float";
            case Type.DOUBLE -> "java/lang/Double";
            default -> null;
        };
    }

    MethodNode boxedCallback(BoxedCallback callback, int instructionIndex, Type receiver) {
        return create(instructionIndex, receiver, Type.getReturnType(callback.unbox.desc),
            Type.getArgumentTypes(callback.box.desc), callback.box.clone(null), callback.call.clone(null),
            callback.cast.clone(null), callback.unbox.clone(null));
    }

    MethodNode call(MethodInsnNode call, int instructionIndex, Type receiver, Type[] parameters) {
        var operation = new MethodInsnNode(call.getOpcode(), call.owner, call.name, call.desc, call.itf);
        return create(instructionIndex, receiver, Type.getReturnType(call.desc), parameters, operation);
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

    MethodNode throwException(int instructionIndex) {
        Type[] arguments = {Type.getType(Throwable.class)};
        return create(instructionIndex, null, Type.VOID_TYPE, arguments, new InsnNode(ATHROW));
    }

    static boolean supportsDynamic(InvokeDynamicInsnNode dynamic) {
        Handle bootstrap = dynamic.bsm;
        if (bootstrap.getTag() != H_INVOKESTATIC || bootstrap.isInterface()) return false;
        return switch (bootstrap.getOwner()) {
            case "java/lang/invoke/StringConcatFactory" -> stringConcat(dynamic, bootstrap);
            case "java/lang/invoke/LambdaMetafactory" -> lambdaFactory(dynamic, bootstrap);
            default -> false;
        };
    }

    private static boolean stringConcat(InvokeDynamicInsnNode dynamic, Handle bootstrap) {
        if (!Type.getReturnType(dynamic.desc).equals(Type.getType(String.class))) return false;
        if (bootstrap.getName().equals("makeConcat"))
            return bootstrap.getDesc().equals(CALL_SITE_BOOTSTRAP_PARAMETERS + CALL_SITE) && dynamic.bsmArgs.length == 0;
        if (!bootstrap.getName().equals("makeConcatWithConstants")
            || !bootstrap.getDesc().equals(CALL_SITE_BOOTSTRAP_PARAMETERS + "Ljava/lang/String;[Ljava/lang/Object;" + CALL_SITE)
            || dynamic.bsmArgs.length == 0 || !(dynamic.bsmArgs[0] instanceof String)) return false;
        for (Object constant : dynamic.bsmArgs) {
            if (!(constant instanceof String || constant instanceof Integer || constant instanceof Long
                || constant instanceof Float || constant instanceof Double)) return false;
        }
        return true;
    }

    private static boolean lambdaFactory(InvokeDynamicInsnNode dynamic, Handle bootstrap) {
        Object[] args = dynamic.bsmArgs;
        if (Type.getReturnType(dynamic.desc).getSort() != Type.OBJECT || args.length < 3
            || !(args[0] instanceof Type interfaceMethod) || interfaceMethod.getSort() != Type.METHOD
            || !(args[1] instanceof Handle implementation)
            || implementation.getTag() < H_INVOKEVIRTUAL || implementation.getTag() > H_INVOKEINTERFACE
            || !(args[2] instanceof Type instantiated) || instantiated.getSort() != Type.METHOD) return false;
        if (bootstrap.getName().equals("metafactory"))
            return bootstrap.getDesc().equals(LAMBDA_BOOTSTRAP) && args.length == 3;
        if (!bootstrap.getName().equals("altMetafactory") || !bootstrap.getDesc().equals(ALT_LAMBDA_BOOTSTRAP)
            || args.length < 4 || !(args[3] instanceof Integer flags)) return false;
        int serializable = LambdaMetafactory.FLAG_SERIALIZABLE;
        int withBridges = serializable | LambdaMetafactory.FLAG_BRIDGES;
        // javac also emits the bridges flag followed by a zero bridge count for this form.
        return (flags == serializable && args.length == 4)
            || (flags == withBridges && args.length == 5 && args[4] instanceof Integer bridges && bridges == 0);
    }

    MethodNode dynamic(InvokeDynamicInsnNode dynamic, int instructionIndex) {
        // Keep one instruction per original site and let the JVM link it using this caller class.
        return create(instructionIndex, null, Type.getReturnType(dynamic.desc),
            Type.getArgumentTypes(dynamic.desc), dynamic.clone(null));
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

    MethodNode catchException(int instructionIndex, List<TryCatchBlockNode> handlers) {
        String throwable = "java/lang/Throwable";
        MethodNode bridge = newMethod(instructionIndex + "$catch", "(Ljava/lang/Throwable;)I");
        var start = new LabelNode();
        var end = new LabelNode();
        bridge.instructions.add(start);
        bridge.instructions.add(new VarInsnNode(ALOAD, 0));
        bridge.instructions.add(new InsnNode(ATHROW));
        bridge.instructions.add(end);
        for (int i = 0; i < handlers.size(); i++) {
            String type = handlers.get(i).type;
            var target = new LabelNode();
            bridge.tryCatchBlocks.add(new TryCatchBlockNode(start, end, target, type));
            bridge.instructions.add(target);
            bridge.instructions.add(new FrameNode(F_NEW, 1, new Object[]{throwable},
                1, new Object[]{type == null ? throwable : type}));
            bridge.instructions.add(new InsnNode(POP));
            bridge.instructions.add(new LdcInsnNode(i));
            bridge.instructions.add(new InsnNode(IRETURN));
        }
        bridge.maxLocals = 1;
        bridge.maxStack = 1;
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
        MethodNode bridge = newMethod(Integer.toString(instructionIndex), descriptor);
        int slots = 0;
        for (int i = 0; i < parameters.length; i++) {
            Type parameter = parameters[i];
            // Retain the verifier's null type for an operation with a literal null receiver.
            bridge.instructions.add(nullReceiver && i == 0
                ? new InsnNode(ACONST_NULL) : new VarInsnNode(parameter.getOpcode(ILOAD), slots));
            slots += parameter.getSize();
        }
        for (AbstractInsnNode operation : operations) bridge.instructions.add(operation);
        if (operations[operations.length - 1].getOpcode() != ATHROW)
            bridge.instructions.add(new InsnNode(returned.getOpcode(IRETURN)));
        bridge.maxLocals = slots;
        bridge.maxStack = Math.max(slots, returned.getSize());
        return bridge;
    }

    private MethodNode newMethod(String site, String descriptor) {
        if (prefix == null) prefix = "$$native$link$" + owner.methods.indexOf(caller) + "$";
        String name = prefix + site;
        while (hasMethod(name, descriptor)) name += "$";
        var bridge = new MethodNode(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, name, descriptor, null, null);
        methods.add(bridge);
        return bridge;
    }

    private boolean hasMethod(String name, String descriptor) {
        return owner.methods.stream().anyMatch(method -> method.name.equals(name) && method.desc.equals(descriptor));
    }
}
