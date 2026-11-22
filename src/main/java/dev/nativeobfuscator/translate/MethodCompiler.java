package dev.nativeobfuscator.translate;

import java.util.List;
import java.util.Locale;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import static org.objectweb.asm.Opcodes.*;

/** Lowers verified stack frames to per-instruction C statements. */
public final class MethodCompiler {
    private static final Type OBJECT_TYPE = Type.getObjectType("java/lang/Object");

    private MethodCompiler() {}

    public static final class Unsupported extends Exception {
        public Unsupported(String message) { super(message); }
    }

    @FunctionalInterface
    public interface WrappingOperation {
        String lower(int instructionIndex, int opcode, String left, String right);
    }

    public static String compile(ClassNode owner, MethodNode method, String symbol) throws Unsupported {
        return compile(owner, method, symbol, "", null);
    }

    public record Compilation(String code, List<MethodNode> bridges) {
        public Compilation {
            bridges = List.copyOf(bridges);
        }
    }

    public static String compile(ClassNode owner, MethodNode method, String symbol,
                                 String methodAttribute, WrappingOperation wrappingOperation) throws Unsupported {
        return compile(owner, method, symbol, methodAttribute, wrappingOperation, null);
    }

    public static Compilation compileWithBridges(ClassNode owner, MethodNode method, String symbol,
                                                 String methodAttribute, WrappingOperation wrappingOperation) throws Unsupported {
        var bridges = new JvmBridges(owner, method);
        String code = compile(owner, method, symbol, methodAttribute, wrappingOperation, bridges);
        return new Compilation(code, bridges.methods());
    }

    public static List<MethodNode> rebuildBridges(ClassNode owner, MethodNode method) throws Unsupported {
        // Use the same verified reachability and deterministic naming as native emission.
        for (AbstractInsnNode instruction : method.instructions) {
            if (JvmBridges.requiresBridge(owner, instruction))
                return compileWithBridges(owner, method, "rebuild_bridges", "", null).bridges();
        }
        return List.of();
    }

    private static String compile(ClassNode owner, MethodNode method, String symbol,
                                  String methodAttribute, WrappingOperation wrappingOperation,
                                  JvmBridges bridges) throws Unsupported {
        if (method.name.startsWith("<") || (method.access & (ACC_ABSTRACT | ACC_NATIVE)) != 0)
            throw new Unsupported("initializer, abstract method, or already native");
        if (!method.tryCatchBlocks.isEmpty())
            throw new Unsupported("exception handlers");
        if (method.instructions.size() > 12000 || method.maxLocals > 1024 || method.maxStack > 1024)
            throw new Unsupported("method exceeds translation limits");
        final Frame<BasicValue>[] frames;
        try {
            frames = new Analyzer<>(new TypedVerifier()).analyze(owner.name, method);
        } catch (AnalyzerException | RuntimeException e) {
            throw new Unsupported("bytecode verification failed: " + e.getMessage());
        }

        var code = new StringBuilder();
        var type = Type.getMethodType(method.desc);
        boolean isStatic = (method.access & ACC_STATIC) != 0;
        code.append("static ").append(methodAttribute).append(jni(type.getReturnType()))
            .append(" JNICALL ").append(symbol)
            .append("(JNIEnv *env, ").append(isStatic ? "jclass" : "jobject").append(" self");
        Type[] args = type.getArgumentTypes();
        for (int i = 0; i < args.length; i++)
            code.append(", ").append(jni(args[i])).append(" a").append(i);
        code.append(") {\n    (void)env; (void)self;\n    Value s[").append(Math.max(1, method.maxStack))
            .append("] = {0}, l[").append(Math.max(1, method.maxLocals)).append("] = {0};\n")
            .append("    (void)s; (void)l;\n");
        int capacity = referenceCapacity(method);
        if (capacity > 16)
            line(code, "if ((*env)->EnsureLocalCapacity(env, " + capacity + ") < 0) { " + zeroReturn(type.getReturnType()) + " }");
        int local = 0;
        if (!isStatic) {
            line(code, "l[0].r = self;");
            local++;
        }
        for (int i = 0; i < args.length; i++) {
            line(code, "l[" + local + "]." + field(args[i]) + " = a" + i + ";");
            local += args[i].getSize();
        }

        AbstractInsnNode[] insns = method.instructions.toArray();
        var targets = new java.util.HashSet<LabelNode>();
        for (AbstractInsnNode insn : insns) {
            if (insn instanceof JumpInsnNode jump) targets.add(jump.label);
            if (insn instanceof TableSwitchInsnNode table) {
                targets.add(table.dflt);
                targets.addAll(table.labels);
            }
            if (insn instanceof LookupSwitchInsnNode lookup) {
                targets.add(lookup.dflt);
                targets.addAll(lookup.labels);
            }
        }
        for (int i = 0; i < insns.length; i++) {
            if (frames[i] == null) continue;
            AbstractInsnNode insn = insns[i];
            if (insn instanceof LabelNode label && targets.contains(label)) {
                code.append(label(method, label)).append(": ;\n");
            }
            if (insn.getOpcode() >= 0) {
                try {
                    if (insn.getOpcode() == NEW && bridges != null) {
                        var allocation = (TypeInsnNode) insn;
                        MethodInsnNode constructor = JvmBridges.constructor(allocation);
                        int end = method.instructions.indexOf(constructor);
                        MethodNode bridge = bridges.construct(allocation, constructor, i);
                        // NEW and DUP reserve two verifier slots. Plain pushes can fill the
                        // argument slots while the combined JVM operation remains pending.
                        for (int argument = i + 2; argument < end; argument++)
                            emit(code, owner, method, insns[argument], frames[argument], type.getReturnType(), argument,
                                wrappingOperation, bridges);
                        emitCall(code, owner, method, bridge.name, bridge.desc, frames[end], type.getReturnType(),
                            true, false, -2);
                        i = end;
                    } else {
                        emit(code, owner, method, insn, frames[i], type.getReturnType(), i, wrappingOperation, bridges);
                    }
                } catch (Unsupported e) {
                    throw new Unsupported("instruction " + i + " (opcode " + insn.getOpcode() + "): " + e.getMessage());
                }
            }
        }
        return code.append("}\n\n").toString();
    }

    private static void emit(StringBuilder code, ClassNode owner, MethodNode method, AbstractInsnNode insn,
                             Frame<BasicValue> frame, Type result, int instructionIndex,
                             WrappingOperation wrappingOperation, JvmBridges bridges) throws Unsupported {
        int depth = frame.getStackSize();
        String out = "s[" + depth + "]";
        String a = "s[" + (depth - 1) + "]";
        String b = "s[" + (depth - 2) + "]";
        int op = insn.getOpcode();
        switch (op) {
            case NOP -> {}
            case POP -> startOfSlots(frame, depth, 1);
            case POP2 -> startOfSlots(frame, depth, 2);
            case ICONST_M1, ICONST_0, ICONST_1, ICONST_2, ICONST_3, ICONST_4, ICONST_5 ->
                line(code, out + ".i = " + (op - ICONST_0) + ";");
            case LCONST_0, LCONST_1 -> line(code, out + ".j = " + (op - LCONST_0) + ";");
            case FCONST_0, FCONST_1, FCONST_2 -> line(code, out + ".f = " + (op - FCONST_0) + ".0f;");
            case DCONST_0, DCONST_1 -> line(code, out + ".d = " + (op - DCONST_0) + ".0;");
            case ACONST_NULL -> line(code, out + ".r = NULL;");
            case BIPUSH, SIPUSH -> line(code, out + ".i = " + ((IntInsnNode) insn).operand + ";");
            case LDC -> constant(code, out, ((LdcInsnNode) insn).cst);
            case ILOAD, LLOAD, FLOAD, DLOAD, ALOAD ->
                line(code, out + " = l[" + ((VarInsnNode) insn).var + "];");
            case ISTORE, LSTORE, FSTORE, DSTORE, ASTORE ->
                line(code, "l[" + ((VarInsnNode) insn).var + "] = " + a + ";");
            case IINC -> {
                IincInsnNode increment = (IincInsnNode) insn;
                String slot = "l[" + increment.var + "].i";
                line(code, slot + " = bits_i((uint32_t)" + slot + " + (uint32_t)(" + increment.incr + "));");
            }
            case DUP -> duplicate(code, frame, 1, 0);
            case DUP_X1 -> duplicate(code, frame, 1, 1);
            case DUP_X2 -> duplicate(code, frame, 1, 2);
            case DUP2 -> duplicate(code, frame, 2, 0);
            case DUP2_X1 -> duplicate(code, frame, 2, 1);
            case DUP2_X2 -> duplicate(code, frame, 2, 2);
            case SWAP -> {
                startOfSlots(frame, depth, 1);
                startOfSlots(frame, depth - 1, 1);
                line(code, "{ Value tmp = " + a + "; " + a + " = " + b + "; " + b + " = tmp; }");
            }
            case ARRAYLENGTH -> {
                Type array = frame.getStack(depth - 1).getType();
                if (array == null || (array.getSort() != Type.ARRAY && !isNull(array)))
                    throw new Unsupported("array length requires a proven array type");
                nullCheck(code, a + ".r", result);
                line(code, a + ".i = (*env)->GetArrayLength(env, (jarray)" + a + ".r);");
                pending(code, result);
            }
            case IALOAD, LALOAD, FALOAD, DALOAD, AALOAD, BALOAD, CALOAD, SALOAD,
                 IASTORE, LASTORE, FASTORE, DASTORE, AASTORE, BASTORE, CASTORE, SASTORE ->
                arrayAccess(code, method, insn, frame, result);
            case GETFIELD, PUTFIELD, GETSTATIC, PUTSTATIC ->
                fieldAccess(code, owner, method, (FieldInsnNode)insn, frame, result, instructionIndex, bridges);
            case INVOKESTATIC, INVOKEVIRTUAL, INVOKESPECIAL, INVOKEINTERFACE ->
                methodCall(code, owner, method, (MethodInsnNode)insn, frame, result, instructionIndex, bridges);
            case IADD, ISUB, IMUL, IAND, IOR, IXOR, LADD, LSUB, LMUL, LAND, LOR, LXOR -> {
                boolean wide = op == LADD || op == LSUB || op == LMUL || op == LAND || op == LOR || op == LXOR;
                String f = wide ? ".j" : ".i";
                String u = wide ? "uint64_t" : "uint32_t";
                String arithmetic = switch (op) {
                    case IADD, LADD -> "+";
                    case ISUB, LSUB -> "-";
                    case IMUL, LMUL -> "*";
                    case IAND, LAND -> "&";
                    case IOR, LOR -> "|";
                    default -> "^";
                };
                String left = "(" + u + ")" + b + f;
                String right = "(" + u + ")" + a + f;
                String expression = wrappingOperation == null ? null
                    : wrappingOperation.lower(instructionIndex, op, left, right);
                if (expression == null) expression = left + " " + arithmetic + " " + right;
                line(code, b + f + " = bits_" + f.charAt(1) + "(" + expression + ");");
            }
            case INEG -> line(code, a + ".i = bits_i(0U - (uint32_t)" + a + ".i);");
            case LNEG -> line(code, a + ".j = bits_j(UINT64_C(0) - (uint64_t)" + a + ".j);");
            case ISHL, ISHR, IUSHR, LSHL, LSHR, LUSHR -> {
                boolean wide = op == LSHL || op == LSHR || op == LUSHR;
                char f = wide ? 'j' : 'i';
                int width = wide ? 64 : 32;
                String bits = "(uint" + width + "_t)" + b + "." + f;
                String shift = "((uint32_t)" + a + ".i & " + (width - 1) + "U)";
                String expression = switch (op) {
                    case ISHR, LSHR -> "sar_" + f + "(" + bits + ", " + shift + ")";
                    case ISHL, LSHL -> bits + " << " + shift;
                    default -> bits + " >> " + shift;
                };
                line(code, b + "." + f + " = bits_" + f + "(" + expression + ");");
            }
            case IDIV, IREM, LDIV, LREM -> {
                boolean wide = op == LDIV || op == LREM;
                boolean mod = op == IREM || op == LREM;
                String f = wide ? ".j" : ".i";
                String low = wide ? "INT64_MIN" : "INT32_MIN";
                line(code, "if (" + a + f + " == 0) { arithmetic_error(env); " + zeroReturn(result) + " }");
                line(code, b + f + " = (" + b + f + " == " + low + " && " + a + f + " == -1) ? "
                    + (mod ? "0" : low) + " : " + b + f + (mod ? " % " : " / ") + a + f + ";");
            }
            case FADD, FSUB, FMUL, FDIV, DADD, DSUB, DMUL, DDIV -> {
                boolean wide = op == DADD || op == DSUB || op == DMUL || op == DDIV;
                String f = wide ? ".d" : ".f";
                String operator = switch (op) {
                    case FADD, DADD -> "+";
                    case FSUB, DSUB -> "-";
                    case FMUL, DMUL -> "*";
                    default -> "/";
                };
                line(code, b + f + " = " + b + f + " " + operator + " " + a + f + ";");
            }
            case FREM -> line(code, b + ".f = fmodf(" + b + ".f, " + a + ".f);");
            case DREM -> line(code, b + ".d = fmod(" + b + ".d, " + a + ".d);");
            case FNEG -> line(code, a + ".f = -" + a + ".f;");
            case DNEG -> line(code, a + ".d = -" + a + ".d;");
            case I2L -> line(code, a + ".j = " + a + ".i;");
            case I2F -> line(code, a + ".f = (jfloat)" + a + ".i;");
            case I2D -> line(code, a + ".d = (jdouble)" + a + ".i;");
            case L2I -> line(code, a + ".i = bits_i((uint32_t)" + a + ".j);");
            case L2F -> line(code, a + ".f = (jfloat)" + a + ".j;");
            case L2D -> line(code, a + ".d = (jdouble)" + a + ".j;");
            case F2D -> line(code, a + ".d = " + a + ".f;");
            case D2F -> line(code, a + ".f = (jfloat)" + a + ".d;");
            case F2I, D2I -> line(code, a + ".i = fp_i(" + a + (op == F2I ? ".f" : ".d") + ");");
            case F2L, D2L -> line(code, a + ".j = fp_j(" + a + (op == F2L ? ".f" : ".d") + ");");
            case I2B -> line(code, a + ".i = narrow_i(" + a + ".i, 255U, 128U);");
            case I2S -> line(code, a + ".i = narrow_i(" + a + ".i, 65535U, 32768U);");
            case I2C -> line(code, a + ".i = (jint)((uint32_t)" + a + ".i & 65535U);");
            case LCMP, FCMPL, FCMPG, DCMPL, DCMPG -> {
                String f = op == LCMP ? ".j" : (op == FCMPL || op == FCMPG ? ".f" : ".d");
                String otherwise = op == LCMP ? "0" : "((" + b + f + " == " + a + f + ") ? 0 : "
                    + (op == FCMPG || op == DCMPG ? "1" : "-1") + ")";
                line(code, b + ".i = (" + b + f + " < " + a + f + ") ? -1 : ("
                    + b + f + " > " + a + f + ") ? 1 : " + otherwise + ";");
            }
            case IFEQ, IFNE, IFLT, IFGE, IFGT, IFLE ->
                jump(code, method, insn, a + ".i " + compare(op - IFEQ) + " 0");
            case IF_ICMPEQ, IF_ICMPNE, IF_ICMPLT, IF_ICMPGE, IF_ICMPGT, IF_ICMPLE ->
                jump(code, method, insn, b + ".i " + compare(op - IF_ICMPEQ) + " " + a + ".i");
            case IFNULL, IFNONNULL ->
                jump(code, method, insn, a + ".r " + (op == IFNULL ? "==" : "!=") + " NULL");
            case IF_ACMPEQ, IF_ACMPNE ->
                jump(code, method, insn, (op == IF_ACMPNE ? "!" : "")
                    + "(*env)->IsSameObject(env, " + b + ".r, " + a + ".r)");
            case GOTO -> jump(code, method, insn, null);
            case TABLESWITCH -> {
                TableSwitchInsnNode table = (TableSwitchInsnNode) insn;
                line(code, "switch (" + a + ".i) {");
                for (int i = 0; i < table.labels.size(); i++)
                    line(code, "case " + ((long) table.min + i) + ": goto " + label(method, table.labels.get(i)) + ";");
                line(code, "default: goto " + label(method, table.dflt) + "; }");
            }
            case LOOKUPSWITCH -> {
                LookupSwitchInsnNode lookup = (LookupSwitchInsnNode) insn;
                line(code, "switch (" + a + ".i) {");
                for (int i = 0; i < lookup.keys.size(); i++)
                    line(code, "case " + lookup.keys.get(i) + ": goto " + label(method, lookup.labels.get(i)) + ";");
                line(code, "default: goto " + label(method, lookup.dflt) + "; }");
            }
            case RETURN -> line(code, "return;");
            case IRETURN -> {
                String value = switch (result.getSort()) {
                    case Type.BOOLEAN -> "(jboolean)((uint32_t)" + a + ".i & 1U)";
                    case Type.BYTE -> "(jbyte)narrow_i(" + a + ".i, 255U, 128U)";
                    case Type.SHORT -> "(jshort)narrow_i(" + a + ".i, 65535U, 32768U)";
                    case Type.CHAR -> "(jchar)((uint32_t)" + a + ".i & 65535U)";
                    default -> a + ".i";
                };
                line(code, "return " + value + ";");
            }
            case LRETURN, FRETURN, DRETURN, ARETURN -> line(code, "return " + a + "." + field(result) + ";");
            case NEW -> throw new Unsupported("object allocation requires uninitialized-object tracking");
            case NEWARRAY, ANEWARRAY, MULTIANEWARRAY -> {
                if (bridges == null) throw new Unsupported("array allocation requires a JVM bridge");
                Type type = arrayAllocationType(insn);
                MethodNode bridge = bridges.allocateArray(insn, instructionIndex, type);
                emitCall(code, owner, method, bridge.name, bridge.desc, frame, result, true, false);
            }
            case CHECKCAST, INSTANCEOF -> {
                if (bridges == null) throw new Unsupported("reference type resolution requires a JVM bridge");
                MethodNode bridge = bridges.typeCheck((TypeInsnNode) insn, instructionIndex);
                emitCall(code, owner, method, bridge.name, bridge.desc, frame, result, true, false);
            }
            case ATHROW -> throw new Unsupported("explicit throw and null-throw semantics are not verified");
            case MONITORENTER, MONITOREXIT -> throw new Unsupported("explicit monitor ownership and exception cleanup are not verified");
            case INVOKEDYNAMIC -> {
                var dynamic = (InvokeDynamicInsnNode) insn;
                if (bridges == null || !JvmBridges.stringConcat(dynamic))
                    throw new Unsupported("dynamic call-site linkage is not verified for this bootstrap");
                MethodNode bridge = bridges.concat(dynamic, instructionIndex);
                emitCall(code, owner, method, bridge.name, bridge.desc, frame, result, true, false);
            }
            default -> throw new Unsupported("unsupported operation");
        }
    }


    /** Keeps exact descriptor types without loading application classes into the tool VM.
     * Hierarchy-dependent assignments are deliberately rejected rather than guessed. */
    private static final class TypedVerifier extends BasicVerifier {
        TypedVerifier() { super(ASM9); }
        @Override public BasicValue newValue(Type type) {
            if (type != null && (type.getSort() == Type.ARRAY || type.getSort() == Type.OBJECT || isNarrow(type)))
                return new BasicValue(type);
            return super.newValue(type);
        }
        @Override public BasicValue copyOperation(AbstractInsnNode insn, BasicValue value) throws AnalyzerException {
            if ((insn.getOpcode() == ILOAD || insn.getOpcode() == ISTORE) && isNarrow(value.getType())) return value;
            return super.copyOperation(insn, value);
        }
        @Override public BasicValue newOperation(AbstractInsnNode insn) throws AnalyzerException {
            BasicValue result = super.newOperation(insn);
            int opcode = insn.getOpcode();
            if (opcode >= ICONST_M1 && opcode <= ICONST_5) return integralConstant(opcode - ICONST_0);
            if (opcode == BIPUSH || opcode == SIPUSH) return integralConstant(((IntInsnNode) insn).operand);
            if (insn instanceof LdcInsnNode constant && constant.cst instanceof Integer number) return integralConstant(number);
            return result;
        }
        @Override public BasicValue unaryOperation(AbstractInsnNode insn, BasicValue value) throws AnalyzerException {
            BasicValue result = super.unaryOperation(insn, value);
            return switch (insn.getOpcode()) {
                case I2B -> newValue(Type.BYTE_TYPE);
                case I2C -> newValue(Type.CHAR_TYPE);
                case I2S -> newValue(Type.SHORT_TYPE);
                default -> result;
            };
        }
        @Override public BasicValue binaryOperation(AbstractInsnNode insn, BasicValue left, BasicValue right) throws AnalyzerException {
            BasicValue result = super.binaryOperation(insn, left, right);
            return switch (insn.getOpcode()) {
                case BALOAD -> newValue(Type.getType("[Z").equals(left.getType()) ? Type.BOOLEAN_TYPE : Type.BYTE_TYPE);
                case CALOAD -> newValue(Type.CHAR_TYPE);
                case SALOAD -> newValue(Type.SHORT_TYPE);
                default -> result;
            };
        }
        @Override public BasicValue ternaryOperation(AbstractInsnNode insn, BasicValue array, BasicValue index,
                                                    BasicValue value) throws AnalyzerException {
            // BasicVerifier checks array-store indices by equality against INT_VALUE.
            // Narrow integer values use the same JVM int stack category.
            if (isNarrow(index.getType())) index = BasicValue.INT_VALUE;
            return super.ternaryOperation(insn, array, index, value);
        }
        private BasicValue integralConstant(int value) {
            if (value == 0 || value == 1) return newValue(Type.BOOLEAN_TYPE);
            if (value == (byte) value) return newValue(Type.BYTE_TYPE);
            if (value == (short) value) return newValue(Type.SHORT_TYPE);
            if (value == (char) value) return newValue(Type.CHAR_TYPE);
            return BasicValue.INT_VALUE;
        }
        @Override protected boolean isArrayValue(BasicValue value) {
            return value.getType() != null && (value.getType().getSort() == Type.ARRAY || isNull(value.getType()));
        }
        @Override protected BasicValue getElementValue(BasicValue value) throws AnalyzerException {
            Type type = value.getType();
            if (type != null && isNull(type)) return value;
            if (type != null && type.getSort() == Type.ARRAY)
                return newValue(arrayComponent(type));
            throw new AnalyzerException(null, "array operand requires a proven array type");
        }
        @Override protected boolean isSubTypeOf(BasicValue value, BasicValue expected) {
            if (value.equals(expected)) return true;
            if (isStackInt(value.getType()) && isStackInt(expected.getType())) return true;
            if (value.isReference() && expected.isReference()) {
                if (isNull(value.getType())) return true;
                return referenceSubtype(value.getType(), expected.getType());
            }
            return false;
        }
        @Override public BasicValue merge(BasicValue left, BasicValue right) {
            if (left.equals(right)) return left;
            if (isStackInt(left.getType()) && isStackInt(right.getType())) return BasicValue.INT_VALUE;
            if (left.isReference() && right.isReference())
                return newValue(mergeReferenceTypes(left.getType(), right.getType()));
            return BasicValue.UNINITIALIZED_VALUE;
        }
    }

    private static Type arrayAllocationType(AbstractInsnNode insn) throws Unsupported {
        if (insn instanceof MultiANewArrayInsnNode multi) return Type.getType(multi.desc);
        if (insn instanceof TypeInsnNode reference) return Type.getType("[" + Type.getObjectType(reference.desc).getDescriptor());
        int element = ((IntInsnNode) insn).operand;
        return switch (element) {
            case T_BOOLEAN -> Type.getType("[Z");
            case T_CHAR -> Type.getType("[C");
            case T_FLOAT -> Type.getType("[F");
            case T_DOUBLE -> Type.getType("[D");
            case T_BYTE -> Type.getType("[B");
            case T_SHORT -> Type.getType("[S");
            case T_INT -> Type.getType("[I");
            case T_LONG -> Type.getType("[J");
            default -> throw new Unsupported("invalid primitive array type " + element);
        };
    }

    private static boolean isNarrow(Type type) {
        return type != null && type.getSort() >= Type.BOOLEAN && type.getSort() <= Type.SHORT;
    }

    private static boolean isStackInt(Type type) {
        return Type.INT_TYPE.equals(type) || isNarrow(type);
    }

    private static boolean referenceSubtype(Type actual, Type expected) {
        if (actual.equals(expected) || isNull(actual)) return true;
        if (expected.equals(OBJECT_TYPE)) return true;
        if (actual.getSort() == Type.ARRAY && expected.getSort() == Type.ARRAY) {
            Type left = arrayComponent(actual);
            Type right = arrayComponent(expected);
            if (isReferenceType(left) && isReferenceType(right))
                return referenceSubtype(left, right);
        }
        return false;
    }

    private static Type mergeReferenceTypes(Type left, Type right) {
        if (left.equals(right) || isNull(right)) return left;
        if (isNull(left)) return right;
        if (left.getSort() == Type.ARRAY && right.getSort() == Type.ARRAY) {
            Type leftComponent = arrayComponent(left);
            Type rightComponent = arrayComponent(right);
            // If both arrays contain references, their component types have a
            // safe common supertype even when neither component class is available.
            if (isReferenceType(leftComponent) && isReferenceType(rightComponent))
                return Type.getType("[" + mergeReferenceTypes(leftComponent, rightComponent).getDescriptor());
        }
        return OBJECT_TYPE;
    }

    private static Type arrayComponent(Type array) {
        return Type.getType(array.getDescriptor().substring(1));
    }

    private static boolean isReferenceType(Type type) {
        return type.getSort() == Type.ARRAY || type.getSort() == Type.OBJECT;
    }

    private static boolean isNull(Type type) {
        return type != null && type.equals(BasicInterpreter.NULL_TYPE);
    }

    private static void pending(StringBuilder code, Type result) {
        line(code, "if ((*env)->ExceptionCheck(env)) { " + zeroReturn(result) + " }");
    }

    private static void nullCheck(StringBuilder code, String reference, Type result) {
        line(code, "if (" + reference + " == NULL) { null_error(env); " + zeroReturn(result) + " }");
    }

    private static int referenceCapacity(MethodNode method) {
        int produced = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            int opcode = instruction.getOpcode();
            if (opcode == AALOAD || opcode == NEW || JvmBridges.arrayAllocation(opcode)) produced++;
            else if (instruction instanceof InvokeDynamicInsnNode dynamic && JvmBridges.stringConcat(dynamic)) produced++;
            else if (instruction instanceof FieldInsnNode member
                && (member.getOpcode() == GETFIELD || member.getOpcode() == GETSTATIC)) {
                Type type = Type.getType(member.desc);
                if (type.getSort() == Type.ARRAY || type.getSort() == Type.OBJECT) produced++;
            } else if (instruction instanceof MethodInsnNode member) {
                Type type = Type.getReturnType(member.desc);
                if (type.getSort() == Type.ARRAY || type.getSort() == Type.OBJECT) produced++;
            }
        }
        // One declaring-class lookup and one exception helper may coexist with the
        // bounded reference results. Small methods use JNI's guaranteed 16 slots.
        return produced + 2;
    }

    private static boolean hasCycle(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) {
            int index = method.instructions.indexOf(instruction);
            if (instruction instanceof JumpInsnNode jump && method.instructions.indexOf(jump.label) <= index) return true;
            if (instruction instanceof TableSwitchInsnNode table &&
                (method.instructions.indexOf(table.dflt) <= index || table.labels.stream().anyMatch(l -> method.instructions.indexOf(l) <= index))) return true;
            if (instruction instanceof LookupSwitchInsnNode lookup &&
                (method.instructions.indexOf(lookup.dflt) <= index || lookup.labels.stream().anyMatch(l -> method.instructions.indexOf(l) <= index))) return true;
        }
        return false;
    }

    private static void referenceResult(MethodNode method, Type type) throws Unsupported {
        if ((type.getSort() == Type.ARRAY || type.getSort() == Type.OBJECT) && hasCycle(method))
            throw new Unsupported("reference-producing operation in a cyclic method requires bounded JNI local-reference lifetime");
    }

    private static String stack(int index) { return "s[" + index + "]"; }

    private static void arrayAccess(StringBuilder code, MethodNode method, AbstractInsnNode insn,
                                    Frame<BasicValue> frame, Type result) throws Unsupported {
        int op = insn.getOpcode();
        boolean store = op >= IASTORE && op <= SASTORE;
        int base = frame.getStackSize() - (store ? 3 : 2);
        Type array = frame.getStack(base).getType();
        if (isNull(array)) {
            line(code, "null_error(env); " + zeroReturn(result));
            return;
        }
        if (array == null || array.getSort() != Type.ARRAY)
            throw new Unsupported("array access requires an exact array descriptor (including byte versus boolean)");
        Type element = arrayComponent(array);
        String kind = switch (element.getSort()) {
            case Type.BOOLEAN -> "Boolean";
            case Type.BYTE -> "Byte";
            case Type.CHAR -> "Char";
            case Type.SHORT -> "Short";
            case Type.INT -> "Int";
            case Type.LONG -> "Long";
            case Type.FLOAT -> "Float";
            case Type.DOUBLE -> "Double";
            case Type.OBJECT, Type.ARRAY -> "Object";
            default -> throw new Unsupported("unsupported array element descriptor");
        };
        int expected = switch (element.getSort()) {
            case Type.BOOLEAN, Type.BYTE -> BALOAD;
            case Type.CHAR -> CALOAD;
            case Type.SHORT -> SALOAD;
            case Type.INT -> IALOAD;
            case Type.LONG -> LALOAD;
            case Type.FLOAT -> FALOAD;
            case Type.DOUBLE -> DALOAD;
            default -> AALOAD;
        };
        if (op != expected + (store ? IASTORE - IALOAD : 0))
            throw new Unsupported("array opcode disagrees with element descriptor " + array.getDescriptor());
        if (!store) referenceResult(method, element);
        String ref = stack(base) + ".r", index = stack(base + 1) + ".i";
        nullCheck(code, ref, result);
        line(code, "{ jsize length = (*env)->GetArrayLength(env, (jarray)" + ref + ");");
        pending(code, result);
        line(code, "if (" + index + " < 0 || " + index + " >= length) { bounds_error(env, " + index + ", length); " + zeroReturn(result) + " }");
        if (kind.equals("Object")) {
            if (store)
                line(code, "(*env)->SetObjectArrayElement(env, (jobjectArray)" + ref + ", " + index + ", " + stack(base + 2) + ".r);");
            else line(code, ref + " = (*env)->GetObjectArrayElement(env, (jobjectArray)" + ref + ", " + index + ");");
        } else {
            String arrayType = "j" + kind.toLowerCase(Locale.ROOT) + "Array";
            line(code, jni(element) + " element;");
            if (store) {
                line(code, "element = " + narrowed(element, stack(base + 2) + "." + field(element)) + ";");
                line(code, "(*env)->Set" + kind + "ArrayRegion(env, (" + arrayType + ")" + ref + ", " + index + ", 1, &element);");
            } else {
                line(code, "(*env)->Get" + kind + "ArrayRegion(env, (" + arrayType + ")" + ref + ", " + index + ", 1, &element);");
                pending(code, result);
                line(code, stack(base) + "." + field(element) + " = element;");
            }
        }
        pending(code, result);
        line(code, "}");
    }

    private static String narrowed(Type type, String expression) {
        return switch (type.getSort()) {
            case Type.BOOLEAN -> "(jboolean)((uint32_t)(" + expression + ") & 1U)";
            case Type.BYTE -> "(jbyte)narrow_i(" + expression + ", 255U, 128U)";
            case Type.CHAR -> "(jchar)((uint32_t)(" + expression + ") & 65535U)";
            case Type.SHORT -> "(jshort)narrow_i(" + expression + ", 65535U, 32768U)";
            default -> expression;
        };
    }

    private static String suffix(Type type) {
        return switch (type.getSort()) {
            case Type.VOID -> "Void";
            case Type.BOOLEAN -> "Boolean";
            case Type.BYTE -> "Byte";
            case Type.CHAR -> "Char";
            case Type.SHORT -> "Short";
            case Type.INT -> "Int";
            case Type.LONG -> "Long";
            case Type.FLOAT -> "Float";
            case Type.DOUBLE -> "Double";
            default -> "Object";
        };
    }

    private static String literal(String value) {
        // JNI takes modified UTF-8. Octal escapes prevent adjacent digits extending an escape.
        var encoded = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            int c = value.charAt(i);
            if (c >= 1 && c <= 127) encoded.append(String.format("\\%03o", c));
            else if (c <= 2047) {
                encoded.append(String.format("\\%03o\\%03o", 0xc0 | (c >> 6), 0x80 | (c & 63)));
            } else encoded.append(String.format("\\%03o\\%03o\\%03o", 0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63)));
        }
        return encoded.append('"').toString();
    }

    private static void fieldAccess(StringBuilder code, ClassNode owner, MethodNode method, FieldInsnNode insn,
                                    Frame<BasicValue> frame, Type result, int instructionIndex, JvmBridges bridges) throws Unsupported {
        boolean statik = insn.getOpcode() == GETSTATIC || insn.getOpcode() == PUTSTATIC;
        boolean store = insn.getOpcode() == PUTFIELD || insn.getOpcode() == PUTSTATIC;
        int depth = frame.getStackSize();
        boolean external = !insn.owner.equals(owner.name);
        FieldNode declaration = external ? null : JvmBridges.declaredField(owner, insn);
        if (!external) {
            if (declaration == null) throw new Unsupported("field is not declared by the current class");
            if (statik != ((declaration.access & ACC_STATIC) != 0))
                throw new Unsupported("field staticness disagrees with opcode");
            if (store && (declaration.access & ACC_FINAL) != 0)
                throw new Unsupported("writes to final fields require JVM initializer checks");
        }
        if (external || JvmBridges.isVolatile(declaration)) {
            if (bridges == null) throw new Unsupported("external and volatile field operations require a JVM bridge");
            Type receiver = statik ? null : frame.getStack(depth - (store ? 2 : 1)).getType();
            MethodNode bridge = bridges.field(insn, instructionIndex, receiver, store);
            emitCall(code, owner, method, bridge.name, bridge.desc, frame, result, true, false);
            return;
        }
        Type type = Type.getType(insn.desc);
        if (!store) referenceResult(method, type);
        String receiver = statik ? "declaring" : stack(depth - (store ? 2 : 1)) + ".r";
        if (!statik) nullCheck(code, receiver, result);
        line(code, "{ jclass declaring = (*env)->FindClass(env, " + literal(owner.name) + ");");
        pending(code, result);
        line(code, "jfieldID member = (*env)->Get" + (statik ? "Static" : "") + "FieldID(env, declaring, " + literal(insn.name) + ", " + literal(insn.desc) + ");");
        line(code, "if ((*env)->ExceptionCheck(env)) { (*env)->DeleteLocalRef(env, declaring); " + zeroReturn(result) + " }");
        String api = (store ? "Set" : "Get") + (statik ? "Static" : "") + suffix(type) + "Field";
        String call = "(*env)->" + api + "(env, " + receiver + ", member" +
            (store ? ", " + narrowed(type, stack(depth - 1) + "." + field(type)) : "") + ")";
        if (store) line(code, call + ";");
        else line(code, stack(depth - (statik ? 0 : 1)) + "." + field(type) + " = " + call + ";");
        line(code, "(*env)->DeleteLocalRef(env, declaring);");
        pending(code, result);
        line(code, "}");
    }

    private static void methodCall(StringBuilder code, ClassNode owner, MethodNode method, MethodInsnNode insn,
                                   Frame<BasicValue> frame, Type result, int instructionIndex, JvmBridges bridges) throws Unsupported {
        if (insn.name.startsWith("<")) throw new Unsupported("constructor calls require uninitialized-object tracking");
        if (!insn.owner.equals(owner.name)) {
            if (bridges == null || !JvmBridges.externalCall(owner, insn))
                throw new Unsupported("direct call resolution outside the declaring class is not verified for this opcode");
            Type receiver = insn.getOpcode() == INVOKESTATIC ? null
                : frame.getStack(frame.getStackSize() - Type.getArgumentCount(insn.desc) - 1).getType();
            MethodNode bridge = bridges.call(insn, instructionIndex, receiver);
            emitCall(code, owner, method, bridge.name, bridge.desc, frame, result, true, false);
            return;
        }
        MethodNode declaration = owner.methods.stream().filter(m -> m.name.equals(insn.name) && m.desc.equals(insn.desc)).findFirst().orElse(null);
        if (declaration == null) throw new Unsupported("call target is not declared by the current class");
        boolean statik = insn.getOpcode() == INVOKESTATIC;
        boolean special = insn.getOpcode() == INVOKESPECIAL;
        if (insn.itf || insn.getOpcode() == INVOKEINTERFACE)
            throw new Unsupported("interface method resolution is not verified");
        if (statik != ((declaration.access & ACC_STATIC) != 0)) throw new Unsupported("call staticness disagrees with opcode");
        if (special && (declaration.access & ACC_PRIVATE) == 0)
            throw new Unsupported("non-private invokespecial dispatch is not verified");
        emitCall(code, owner, method, insn.name, insn.desc, frame, result, statik,
            !statik && (declaration.access & ACC_PRIVATE) != 0);
    }

    private static void emitCall(StringBuilder code, ClassNode owner, MethodNode method, String name, String descriptor,
                                 Frame<BasicValue> frame, Type result, boolean statik, boolean nonvirtual) throws Unsupported {
        emitCall(code, owner, method, name, descriptor, frame, result, statik, nonvirtual, 0);
    }

    private static void emitCall(StringBuilder code, ClassNode owner, MethodNode method, String name, String descriptor,
                                 Frame<BasicValue> frame, Type result, boolean statik, boolean nonvirtual,
                                 int resultOffset) throws Unsupported {
        Type returned = Type.getReturnType(descriptor);
        referenceResult(method, returned);
        Type[] parameters = Type.getArgumentTypes(descriptor);
        int base = frame.getStackSize() - parameters.length - (statik ? 0 : 1);
        // JVM int-stack arguments can contain values the corresponding JNI type would truncate.
        for (int i = 0; i < parameters.length; i++) {
            Type parameter = parameters[i];
            Type actual = frame.getStack(base + (statik ? 0 : 1) + i).getType();
            if (isNarrow(parameter) && !parameter.equals(actual) && !Type.BOOLEAN_TYPE.equals(actual)
                && !(Type.SHORT_TYPE.equals(parameter) && Type.BYTE_TYPE.equals(actual)))
                throw new Unsupported("argument " + (i + 1) + " is not proven to fit JNI descriptor " + parameter);
        }
        String receiver = statik ? "declaring" : stack(base) + ".r";
        if (!statik) nullCheck(code, receiver, result);
        line(code, "{ jclass declaring = (*env)->FindClass(env, " + literal(owner.name) + ");");
        pending(code, result);
        line(code, "jmethodID member = (*env)->Get" + (statik ? "Static" : "") + "MethodID(env, declaring, " + literal(name) + ", " + literal(descriptor) + ");");
        // DeleteLocalRef is permitted while a Java exception is pending.
        line(code, "if ((*env)->ExceptionCheck(env)) { (*env)->DeleteLocalRef(env, declaring); " + zeroReturn(result) + " }");
        line(code, "jvalue arguments[" + Math.max(1, parameters.length) + "] = {{0}};");
        for (int i = 0; i < parameters.length; i++) {
            Type parameter = parameters[i];
            char member = switch (parameter.getSort()) {
                case Type.BOOLEAN -> 'z'; case Type.BYTE -> 'b'; case Type.CHAR -> 'c'; case Type.SHORT -> 's';
                case Type.INT -> 'i'; case Type.LONG -> 'j'; case Type.FLOAT -> 'f'; case Type.DOUBLE -> 'd'; default -> 'l';
            };
            line(code, "arguments[" + i + "]." + member + " = " + narrowed(parameter, stack(base + (statik ? 0 : 1) + i) + "." + field(parameter)) + ";");
        }
        String api = "Call" + (statik ? "Static" : nonvirtual ? "Nonvirtual" : "") + suffix(returned) + "MethodA";
        String call = "(*env)->" + api + "(env, " + receiver + (nonvirtual ? ", declaring" : "") + ", member, arguments)";
        if (returned.getSort() == Type.VOID) line(code, call + ";");
        else line(code, stack(base + resultOffset) + "." + field(returned) + " = " + call + ";");
        line(code, "(*env)->DeleteLocalRef(env, declaring);");
        pending(code, result);
        line(code, "}");
    }

    private static void duplicate(StringBuilder code, Frame<BasicValue> frame,
                                  int topSlots, int belowSlots) throws Unsupported {
        int depth = frame.getStackSize();
        int top = startOfSlots(frame, depth, topSlots);
        int bottom = startOfSlots(frame, top, belowSlots);
        int count = depth - top;
        line(code, "{ Value copy[" + count + "]; memcpy(copy, &s[" + top + "], sizeof copy);"
            + " memmove(&s[" + (bottom + count) + "], &s[" + bottom + "], "
            + (depth - bottom) + " * sizeof(Value));"
            + " memcpy(&s[" + bottom + "], copy, sizeof copy); }");
    }

    private static int startOfSlots(Frame<BasicValue> frame, int end, int slots) throws Unsupported {
        while (slots > 0 && end > 0) slots -= frame.getStack(--end).getSize();
        if (slots != 0) throw new Unsupported("invalid stack category for duplication");
        return end;
    }

    private static String zeroReturn(Type type) {
        return type.getSort() == Type.VOID ? "return;" : "return (" + jni(type) + ")0;";
    }

    private static void constant(StringBuilder code, String slot, Object value) throws Unsupported {
        if (value instanceof Integer i)
            line(code, slot + ".i = bits_i(UINT32_C(" + Integer.toUnsignedString(i) + "));");
        else if (value instanceof Long l)
            line(code, slot + ".j = bits_j(UINT64_C(" + Long.toUnsignedString(l) + "));");
        else if (value instanceof Float f)
            line(code, slot + ".f = bits_f(UINT32_C(" + Integer.toUnsignedString(Float.floatToRawIntBits(f)) + "));");
        else if (value instanceof Double d)
            line(code, slot + ".d = bits_d(UINT64_C(" + Long.toUnsignedString(Double.doubleToRawLongBits(d)) + "));");
        else throw new Unsupported("constant requires JVM linkage");
    }

    private static void jump(StringBuilder code, MethodNode method, AbstractInsnNode node, String condition) {
        line(code, (condition == null ? "" : "if (" + condition + ") ") + "goto "
            + label(method, ((JumpInsnNode) node).label) + ";");
    }

    private static String compare(int offset) {
        return switch (offset) {
            case 0 -> "==";
            case 1 -> "!=";
            case 2 -> "<";
            case 3 -> ">=";
            case 4 -> ">";
            case 5 -> "<=";
            default -> throw new IllegalArgumentException();
        };
    }

    private static String label(MethodNode method, LabelNode label) {
        return "b" + method.instructions.indexOf(label);
    }

    private static void line(StringBuilder code, String statement) {
        code.append("    ").append(statement).append('\n');
    }

    private static String jni(Type type) {
        return switch (type.getSort()) {
            case Type.VOID -> "void";
            case Type.BOOLEAN -> "jboolean";
            case Type.BYTE -> "jbyte";
            case Type.CHAR -> "jchar";
            case Type.SHORT -> "jshort";
            case Type.INT -> "jint";
            case Type.LONG -> "jlong";
            case Type.FLOAT -> "jfloat";
            case Type.DOUBLE -> "jdouble";
            default -> "jobject";
        };
    }

    private static char field(Type type) {
        return switch (type.getSort()) {
            case Type.LONG -> 'j';
            case Type.FLOAT -> 'f';
            case Type.DOUBLE -> 'd';
            case Type.ARRAY, Type.OBJECT -> 'r';
            default -> 'i';
        };
    }

    public static String runtimeSupport() {
        return """
            #include <jni.h>
            #include <stdint.h>
            #include <math.h>
            #include <string.h>
            #include <stdio.h>
            typedef union { jint i; jlong j; jfloat f; jdouble d; jobject r; } Value;

            static inline jint bits_i(uint32_t bits) { jint value; memcpy(&value, &bits, sizeof value); return value; }
            static inline jlong bits_j(uint64_t bits) { jlong value; memcpy(&value, &bits, sizeof value); return value; }
            static inline jfloat bits_f(uint32_t bits) { jfloat value; memcpy(&value, &bits, sizeof value); return value; }
            static inline jdouble bits_d(uint64_t bits) { jdouble value; memcpy(&value, &bits, sizeof value); return value; }

            static inline uint32_t sar_i(uint32_t x, unsigned n) {
                return (x >> n) | ((UINT32_C(0) - (x >> 31)) ^ ((UINT32_C(0) - (x >> 31)) >> n));
            }
            static inline uint64_t sar_j(uint64_t x, unsigned n) {
                return (x >> n) | ((UINT64_C(0) - (x >> 63)) ^ ((UINT64_C(0) - (x >> 63)) >> n));
            }
            static inline jint narrow_i(jint x, uint32_t mask, uint32_t sign) {
                return bits_i((((uint32_t)x & mask) ^ sign) - sign);
            }
            static inline jint fp_i(jdouble x) {
                if (x != x) return 0;
                if (x >= 0x1p31) return INT32_MAX;
                if (x <= -0x1p31) return INT32_MIN;
                return (jint)x;
            }
            static inline jlong fp_j(jdouble x) {
                if (x != x) return 0;
                if (x >= 0x1p63) return INT64_MAX;
                if (x <= -0x1p63) return INT64_MIN;
                return (jlong)x;
            }
            static inline void null_error(JNIEnv *env) {
                jclass error = (*env)->FindClass(env, "java/lang/NullPointerException");
                if (error != NULL) { (*env)->ThrowNew(env, error, ""); (*env)->DeleteLocalRef(env, error); }
            }
            static inline void bounds_error(JNIEnv *env, jint index, jsize length) {
                char message[96];
                snprintf(message, sizeof message, "Index %d out of bounds for length %d", (int)index, (int)length);
                jclass error = (*env)->FindClass(env, "java/lang/ArrayIndexOutOfBoundsException");
                if (error != NULL) { (*env)->ThrowNew(env, error, message); (*env)->DeleteLocalRef(env, error); }
            }
            static inline void arithmetic_error(JNIEnv *env) {
                jclass error = (*env)->FindClass(env, "java/lang/ArithmeticException");
                if (error != NULL) { (*env)->ThrowNew(env, error, "/ by zero"); (*env)->DeleteLocalRef(env, error); }
            }

            """;
    }
}
