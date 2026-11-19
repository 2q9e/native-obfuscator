package dev.nativeobfuscator.translate;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import static org.objectweb.asm.Opcodes.*;

/** Lowers verified stack frames to per-instruction C statements. */
public final class MethodCompiler {
    private MethodCompiler() {}

    public static final class Unsupported extends Exception {
        public Unsupported(String message) { super(message); }
    }

    public static String compile(ClassNode owner, MethodNode method, String symbol) throws Unsupported {
        if (method.name.startsWith("<") || (method.access & (ACC_ABSTRACT | ACC_NATIVE)) != 0)
            throw new Unsupported("initializer, abstract method, or already native");
        if (!method.tryCatchBlocks.isEmpty())
            throw new Unsupported("exception handlers");
        if (method.instructions.size() > 12000 || method.maxLocals > 1024 || method.maxStack > 1024)
            throw new Unsupported("method exceeds translation limits");
        final Frame<BasicValue>[] frames;
        try {
            frames = new Analyzer<>(new BasicVerifier()).analyze(owner.name, method);
        } catch (AnalyzerException | RuntimeException e) {
            throw new Unsupported("bytecode verification failed: " + e.getMessage());
        }

        var code = new StringBuilder();
        var type = Type.getMethodType(method.desc);
        boolean isStatic = (method.access & ACC_STATIC) != 0;
        code.append("static ").append(jni(type.getReturnType())).append(" JNICALL ").append(symbol)
            .append("(JNIEnv *env, ").append(isStatic ? "jclass" : "jobject").append(" self");
        Type[] args = type.getArgumentTypes();
        for (int i = 0; i < args.length; i++)
            code.append(", ").append(jni(args[i])).append(" a").append(i);
        code.append(") {\n    (void)env; (void)self;\n    Value s[").append(Math.max(1, method.maxStack))
            .append("] = {0}, l[").append(Math.max(1, method.maxLocals)).append("] = {0};\n")
            .append("    (void)s; (void)l;\n");
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
                    emit(code, method, insn, frames[i], type.getReturnType());
                } catch (Unsupported e) {
                    throw new Unsupported("instruction " + i + " (opcode " + insn.getOpcode() + "): " + e.getMessage());
                }
            }
        }
        return code.append("}\n\n").toString();
    }

    private static void emit(StringBuilder code, MethodNode method, AbstractInsnNode insn,
                             Frame<BasicValue> frame, Type result) throws Unsupported {
        int depth = frame.getStackSize();
        String out = "s[" + depth + "]";
        String a = "s[" + (depth - 1) + "]";
        String b = "s[" + (depth - 2) + "]";
        int op = insn.getOpcode();
        switch (op) {
            case NOP, POP, POP2 -> {}
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
            case SWAP -> line(code, "{ Value tmp = " + a + "; " + a + " = " + b + "; " + b + " = tmp; }");
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
                line(code, b + f + " = bits_" + f.charAt(1) + "((" + u + ")" + b + f
                    + " " + arithmetic + " (" + u + ")" + a + f + ");");
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
            default -> throw new Unsupported("unsupported operation");
        }
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
            static inline void arithmetic_error(JNIEnv *env) {
                jclass error = (*env)->FindClass(env, "java/lang/ArithmeticException");
                if (error != NULL) (*env)->ThrowNew(env, error, "/ by zero");
            }

            """;
    }
}
