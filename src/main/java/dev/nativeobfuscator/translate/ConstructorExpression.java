package dev.nativeobfuscator.translate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

import static org.objectweb.asm.Opcodes.*;

/** Moves a closed constructor expression, with its original evaluation order, into a JVM bridge. */
record ConstructorExpression(MethodInsnNode constructor, List<AbstractInsnNode> arguments,
                             List<Integer> sourceLocals, Type[] parameterTypes, int localSlots) {
    private static final int ARGUMENT_INSTRUCTION_LIMIT = 128;

    private record Capture(Type type, int slot) {}

    static ConstructorExpression plan(TypeInsnNode allocation, MethodNode method, Frame<BasicValue>[] frames)
            throws MethodCompiler.Unsupported {
        AbstractInsnNode duplicate = allocation.getNext();
        if (duplicate == null || duplicate.getOpcode() != DUP) throw unsupported("expected DUP after NEW");
        int base = frames[method.instructions.indexOf(allocation)].getStackSize() + 2;
        var captures = new LinkedHashMap<Integer, Capture>();
        var arguments = new ArrayList<AbstractInsnNode>();
        int slots = 0;
        for (AbstractInsnNode instruction = duplicate.getNext(); instruction != null; instruction = instruction.getNext()) {
            int index = method.instructions.indexOf(instruction);
            Frame<BasicValue> frame = frames[index];
            if (frame == null) throw unsupported("unreachable instruction in argument region");
            if (instruction.getOpcode() < 0)
                throw unsupported("argument region crosses a control-flow or exception boundary or a debug marker");
            int argumentDepth = frame.getStackSize() - base;
            if (matchingConstructor(allocation, instruction)) {
                var constructor = (MethodInsnNode) instruction;
                if (argumentDepth != Type.getArgumentCount(constructor.desc))
                    throw unsupported("constructor stack includes values outside its arguments");
                var parameters = captures.values().stream().map(Capture::type).toArray(Type[]::new);
                return new ConstructorExpression(constructor, arguments, List.copyOf(captures.keySet()), parameters, slots);
            }
            if (arguments.size() >= ARGUMENT_INSTRUCTION_LIMIT)
                throw unsupported("argument region exceeds " + ARGUMENT_INSTRUCTION_LIMIT + " instructions");
            int operands = consumedOperands(instruction);
            if (operands < 0) throw unsupported("unsupported argument opcode " + instruction.getOpcode());
            if (operands > argumentDepth)
                throw unsupported("argument consumes a value below the expression stack");
            if (!(instruction instanceof VarInsnNode load)) {
                arguments.add(instruction.clone(null));
                continue;
            }
            Type type = switch (load.getOpcode()) {
                case ILOAD -> Type.INT_TYPE;
                case LLOAD -> Type.LONG_TYPE;
                case FLOAD -> Type.FLOAT_TYPE;
                case DLOAD -> Type.DOUBLE_TYPE;
                case ALOAD -> frame.getLocal(load.var).getType();
                default -> throw new AssertionError("Unexpected constructor argument load");
            };
            if (BasicInterpreter.NULL_TYPE.equals(type)) {
                arguments.add(new InsnNode(ACONST_NULL));
                continue;
            }
            if (type == null) throw unsupported("local argument type is unknown");
            Capture capture = captures.get(load.var);
            if (capture == null) {
                capture = new Capture(type, slots);
                slots += type.getSize();
                if (slots > 255) throw unsupported("captured locals exceed the JVM method parameter limit");
                captures.put(load.var, capture);
            } else if (!capture.type.equals(type)) {
                throw unsupported("local argument changed type inside the expression");
            }
            arguments.add(new VarInsnNode(load.getOpcode(), capture.slot));
        }
        throw unsupported("missing matching constructor");
    }

    static boolean matchingConstructor(TypeInsnNode allocation, AbstractInsnNode instruction) {
        return instruction instanceof MethodInsnNode call && call.getOpcode() == INVOKESPECIAL && !call.itf
            && call.name.equals("<init>") && call.owner.equals(allocation.desc)
            && Type.getReturnType(call.desc).equals(Type.VOID_TYPE);
    }

    private static int consumedOperands(AbstractInsnNode instruction) {
        return switch (instruction.getOpcode()) {
            case ACONST_NULL, ICONST_M1, ICONST_0, ICONST_1, ICONST_2, ICONST_3, ICONST_4, ICONST_5,
                 LCONST_0, LCONST_1, FCONST_0, FCONST_1, FCONST_2, DCONST_0, DCONST_1, BIPUSH, SIPUSH,
                 ILOAD, LLOAD, FLOAD, DLOAD, ALOAD, GETSTATIC -> 0;
            case LDC -> {
                var constant = (LdcInsnNode) instruction;
                yield constant.cst instanceof Number || JvmBridges.linkedConstantType(constant) != null ? 0 : -1;
            }
            case GETFIELD, INEG, LNEG, CHECKCAST, INSTANCEOF -> 1;
            case IADD, ISUB, IMUL, IDIV, IREM, LADD, LSUB, LMUL, LDIV, LREM -> 2;
            case INVOKESTATIC -> {
                var call = (MethodInsnNode) instruction;
                yield call.name.startsWith("<") ? -1 : Type.getArgumentCount(call.desc);
            }
            default -> -1;
        };
    }

    private static MethodCompiler.Unsupported unsupported(String reason) {
        return new MethodCompiler.Unsupported("object allocation requires a straight-line constructor expression: " + reason);
    }
}
