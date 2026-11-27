package dev.nativeobfuscator.translate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
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
        var boundaries = new HashSet<LabelNode>();
        for (var range : method.tryCatchBlocks) {
            boundaries.add(range.start);
            boundaries.add(range.end);
            boundaries.add(range.handler);
        }
        for (var instruction : method.instructions) {
            if (instruction instanceof JumpInsnNode jump) boundaries.add(jump.label);
            if (instruction instanceof TableSwitchInsnNode table) {
                boundaries.add(table.dflt);
                boundaries.addAll(table.labels);
            }
            if (instruction instanceof LookupSwitchInsnNode lookup) {
                boundaries.add(lookup.dflt);
                boundaries.addAll(lookup.labels);
            }
        }
        AbstractInsnNode duplicate = nextExecutable(allocation, boundaries);
        if (duplicate == null || duplicate.getOpcode() != DUP) throw unsupported("expected DUP after NEW");
        int base = frames[method.instructions.indexOf(allocation)].getStackSize() + 2;
        var captures = new LinkedHashMap<Integer, Capture>();
        var arguments = new ArrayList<AbstractInsnNode>();
        int slots = 0;
        for (AbstractInsnNode instruction = nextExecutable(duplicate, boundaries); instruction != null;
             instruction = nextExecutable(instruction, boundaries)) {
            int index = method.instructions.indexOf(instruction);
            Frame<BasicValue> frame = frames[index];
            if (frame == null) throw unsupported("unreachable instruction in argument region");
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

    private static AbstractInsnNode nextExecutable(AbstractInsnNode current, Set<LabelNode> boundaries)
            throws MethodCompiler.Unsupported {
        var next = current.getNext();
        while (next != null && next.getOpcode() < 0) {
            if (!(next instanceof LineNumberNode || next instanceof LabelNode label && !boundaries.contains(label)))
                throw unsupported("argument region crosses a control-flow or exception boundary");
            next = next.getNext();
        }
        return next;
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
            case GETFIELD, INEG, LNEG, FNEG, DNEG, CHECKCAST, INSTANCEOF, ARRAYLENGTH,
                 I2L, I2F, I2D, L2I, L2F, L2D, F2I, F2L, F2D, D2I, D2L, D2F, I2B, I2C, I2S -> 1;
            case IADD, ISUB, IMUL, IDIV, IREM, LADD, LSUB, LMUL, LDIV, LREM,
                 FADD, FSUB, FMUL, FDIV, FREM, DADD, DSUB, DMUL, DDIV, DREM,
                 IAND, IOR, IXOR, LAND, LOR, LXOR, ISHL, ISHR, IUSHR, LSHL, LSHR, LUSHR,
                 IALOAD, LALOAD, FALOAD, DALOAD, AALOAD, BALOAD, CALOAD, SALOAD -> 2;
            case INVOKESTATIC, INVOKEVIRTUAL, INVOKEINTERFACE -> {
                var call = (MethodInsnNode) instruction;
                yield call.name.startsWith("<") ? -1 : Type.getArgumentCount(call.desc)
                    + (call.getOpcode() == INVOKESTATIC ? 0 : 1);
            }
            default -> -1;
        };
    }

    private static MethodCompiler.Unsupported unsupported(String reason) {
        return new MethodCompiler.Unsupported("object allocation requires a straight-line constructor expression: " + reason);
    }
}
