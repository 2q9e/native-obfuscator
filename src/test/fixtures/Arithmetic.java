import java.util.Random;
import java.util.function.IntUnaryOperator;

public final class Arithmetic {
    private static final int INITIAL_VALUE = calcOverload(17, -29);

    private Arithmetic() {}

    static int calcInt(int left, int right, int shift) {
        int total = (left + right) * (left - right);
        return (total << shift) ^ (right >> shift) ^ (left >>> shift) ^ -right;
    }

    static long calcLong(long left, long right, int shift) {
        long total = (left + right) * (left - right);
        return (total << shift) ^ (right >> shift) ^ (left >>> shift) ^ -right;
    }

    static int calcIntDivide(int left, int right) {
        return (left / right) ^ (left % right);
    }

    static long calcLongDivide(long left, long right) {
        return (left / right) ^ (left % right);
    }

    static int calcDenseSwitch(int key, int value) {
        return switch (key) {
            case 0 -> value + 1;
            case 1 -> value - 7;
            case 2 -> value << 3;
            case 3 -> ~value;
            default -> value ^ -11;
        };
    }

    static int calcSparseSwitch(int key, int value) {
        return switch (key) {
            case -500 -> value + 13;
            case 100 -> value * 101;
            case 10_000 -> value >>> 4;
            default -> -value;
        };
    }

    static int calcLoop(int value, int steps) {
        int total = 7;
        for (int i = 0; i < steps; i++) {
            if ((value & i) == 0) {
                total += value ^ i;
            } else {
                total ^= value - i;
            }
            value = (value << 1) | (value >>> 31);
        }
        return total;
    }

    static float calcFloat(float left, float right, int operation) {
        return switch (operation) {
            case 0 -> left + right;
            case 1 -> left - right;
            case 2 -> left * right;
            case 3 -> left / right;
            case 4 -> left % right;
            default -> -left;
        };
    }

    static double calcDouble(double left, double right, int operation) {
        return switch (operation) {
            case 0 -> left + right;
            case 1 -> left - right;
            case 2 -> left * right;
            case 3 -> left / right;
            case 4 -> left % right;
            default -> -left;
        };
    }

    static int calcFloatToInt(float value) {
        return (int) value;
    }

    static long calcFloatToLong(float value) {
        return (long) value;
    }

    static int calcDoubleToInt(double value) {
        return (int) value;
    }

    static long calcDoubleToLong(double value) {
        return (long) value;
    }

    static int calcOverload(int left, int right) {
        return left + right * 11;
    }

    static long calcOverload(long left, long right) {
        return left - right * 13L;
    }

    int calcInstance(int value) {
        return (value << 3) ^ (value >>> 29);
    }

    static boolean calcReferenceIdentity(Object left, Object right) {
        return left == right;
    }

    static int calcReferenceFlags(Object left, Object right) {
        if (left == null) {
            return right == null ? 3 : 2;
        }
        return left == right ? 1 : 0;
    }

    static int calcLambda(int value) {
        IntUnaryOperator fn = operand -> operand ^ value;
        return fn.applyAsInt(67);
    }

    static int calcArray(int[] array, int index) {
        return array[index];
    }

    static int calcThrow() {
        throw new IllegalStateException("fixture failure");
    }

    public static void main(String[] args) {
        check(INITIAL_VALUE == -302, "class initialization");
        check(calcIntDivide(Integer.MIN_VALUE, -1) == Integer.MIN_VALUE, "int division overflow");
        check(calcLongDivide(Long.MIN_VALUE, -1) == Long.MIN_VALUE, "long division overflow");
        check(calcFloatToInt(Float.NaN) == 0, "float NaN conversion");
        check(calcFloatToLong(Float.POSITIVE_INFINITY) == Long.MAX_VALUE, "float upper saturation");
        check(calcDoubleToInt(Double.NEGATIVE_INFINITY) == Integer.MIN_VALUE, "double lower saturation");
        check(calcDoubleToLong(Double.NaN) == 0L, "double NaN conversion");
        check(Float.floatToRawIntBits(calcFloat(0.0f, -0.0f, 5)) == Integer.MIN_VALUE, "float signed zero");
        check(Double.doubleToRawLongBits(calcDouble(-0.0d, 5.0d, 4)) == Long.MIN_VALUE, "double signed zero");
        check(calcLambda(42) == (42 ^ 67), "lambda fallback");
        int[] array = {5, -7, 9};
        check(calcArray(array, 1) == -7, "array fallback");
        expectException(IllegalStateException.class, "fixture failure", Arithmetic::calcThrow);
        expectException(ArrayIndexOutOfBoundsException.class, "Index 3 out of bounds for length 3",
                () -> calcArray(array, 3));
        expectException(ArithmeticException.class, "/ by zero", () -> calcIntDivide(1, 0));
        expectException(ArithmeticException.class, "/ by zero", () -> calcLongDivide(1L, 0L));

        Digest digest = new Digest(INITIAL_VALUE);
        exerciseIntegers(digest);
        exerciseFloatingPoint(digest);
        exerciseReferences(digest);
        System.out.println(Long.toUnsignedString(digest.value, 16));
    }

    private static void exerciseIntegers(Digest digest) {
        int[] ints = {Integer.MIN_VALUE, Integer.MAX_VALUE, -3, -1, 0, 1, 3, 0x5555_aaaa};
        long[] longs = {Long.MIN_VALUE, Long.MAX_VALUE, -3L, -1L, 0L, 1L, 3L, 0x5555_aaaa_3333_ccccL};
        int[] shifts = {Integer.MIN_VALUE, -129, -64, -33, -1, 0, 1, 31, 32, 63, 64, 255, Integer.MAX_VALUE};
        for (int i = 0; i < ints.length; i++) {
            for (int j = 0; j < ints.length; j++) {
                for (int shift : shifts) {
                    digest.add(calcInt(ints[i], ints[j], shift));
                    digest.add(calcLong(longs[i], longs[j], shift));
                }
                if (ints[j] != 0) {
                    digest.add(calcIntDivide(ints[i], ints[j]));
                    digest.add(calcLongDivide(longs[i], longs[j]));
                }
            }
        }
        Arithmetic instance = new Arithmetic();
        int[] sparseKeys = {-500, 100, 10_000, 677};
        Random random = new Random(0x1bd9_87b5_4a23_fe11L);
        for (int i = 0; i < 1_024; i++) {
            int left = random.nextInt();
            int right = random.nextInt() | 1;
            int shift = random.nextInt();
            long longLeft = random.nextLong();
            long longRight = random.nextLong() | 1L;
            digest.add(calcInt(left, right, shift));
            digest.add(calcLong(longLeft, longRight, shift));
            digest.add(calcIntDivide(left, right));
            digest.add(calcLongDivide(longLeft, longRight));
            digest.add(calcOverload(left, right));
            digest.add(calcOverload(longLeft, longRight));
            digest.add(calcDenseSwitch(i % 5, left));
            digest.add(calcSparseSwitch(sparseKeys[i % sparseKeys.length], right));
            digest.add(calcLoop(left, i % 41));
            digest.add(instance.calcInstance(right));
        }
    }

    private static void exerciseFloatingPoint(Digest digest) {
        float[] floats = {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                -Float.MAX_VALUE, -Float.MIN_NORMAL, -Float.MIN_VALUE, -0.0f, 0.0f, Float.MIN_VALUE,
                Float.MIN_NORMAL, 1.5f, -1.75f, Math.nextDown(0x1.0p31f), 0x1.0p31f, -0x1.0p31f,
                Math.nextDown(0x1.0p63f), 0x1.0p63f, -0x1.0p63f, Float.MAX_VALUE};
        double[] doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                -Double.MAX_VALUE, -Double.MIN_NORMAL, -Double.MIN_VALUE, -0.0d, 0.0d, Double.MIN_VALUE,
                Double.MIN_NORMAL, 1.5d, -1.75d, Math.nextDown(0x1.0p31), 0x1.0p31, -0x1.0p31,
                Math.nextDown(0x1.0p63), 0x1.0p63, -0x1.0p63, Double.MAX_VALUE};
        for (int i = 0; i < floats.length; i++) {
            digest.add(calcFloatToInt(floats[i]));
            digest.add(calcFloatToLong(floats[i]));
            digest.add(calcDoubleToInt(doubles[i]));
            digest.add(calcDoubleToLong(doubles[i]));
            for (int j = 0; j < floats.length; j++) {
                for (int operation = 0; operation < 6; operation++) {
                    // Canonicalize NaN payloads for comparisons across JVMs and architectures.
                    digest.add(Float.floatToIntBits(calcFloat(floats[i], floats[j], operation)));
                    digest.add(Double.doubleToLongBits(calcDouble(doubles[i], doubles[j], operation)));
                }
            }
        }
    }

    private static void exerciseReferences(Digest digest) {
        Object shared = new String("equal");
        Object distinct = new String("equal");
        check(shared.equals(distinct), "reference fixture values");
        check(!calcReferenceIdentity(shared, distinct), "identity between equal values");
        check(calcReferenceIdentity(shared, shared), "identity of a shared reference");
        Object[] references = {shared, distinct, null};
        for (Object left : references) {
            for (Object right : references) {
                digest.add(calcReferenceIdentity(left, right) ? 1 : 0);
                digest.add(calcReferenceFlags(left, right));
            }
        }
    }

    private static void expectException(Class<? extends RuntimeException> type, String message, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            check(failure.getClass() == type, "exception type: " + failure);
            check(message.equals(failure.getMessage()), "exception message: " + failure);
            return;
        }
        throw new AssertionError("missing exception: " + type.getName());
    }

    private static void check(boolean condition, String detail) {
        if (!condition) {
            throw new AssertionError(detail);
        }
    }

    private static final class Digest {
        private long value;

        private Digest(long seed) {
            value = seed;
        }

        private void add(long next) {
            value = Long.rotateLeft(value ^ next, 13) * 0x9e37_79b9_7f4a_7c15L + 1L;
        }
    }
}
