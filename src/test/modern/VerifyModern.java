package modern;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.function.Supplier;

public final class VerifyModern {
    public static void main(String[] args) throws ReflectiveOperationException {
        boolean protectedJar = args[0].equals("protected");
        Class<?> fixture = Class.forName("modern.CondyFixture");
        Class<?> marker = Class.forName("modern.Marker_\uD801\uDC00");
        var condy = fixture.getDeclaredMethod("calcCondy");
        var counter = fixture.getDeclaredField("bootstrapCalls");
        var candidate = fixture.getDeclaredMethod("calc\u03bb\uD801\uDC00", int.class, long.class, marker);
        check(!Modifier.isNative(condy.getModifiers()), "ConstantDynamic method retained in Java");
        check(Modifier.isNative(candidate.getModifiers()) == protectedJar, "Unicode signature JNI flag");
        check(counter.getInt(null) == 0, "bootstrap did not run eagerly");
        Object argument = marker.getDeclaredConstructor().newInstance();
        check((long) candidate.invoke(null, 4, 79L, argument) == 75L, "JNI Unicode signature value");
        check((long) candidate.invoke(null, 4, 79L, null) == 76L, "JNI Unicode signature null reference");
        check((int) condy.invoke(null) == 42 && (int) condy.invoke(null) == 42, "dynamic constant value");
        check(counter.getInt(null) == 1, "same-class bootstrap resolved once");

        check(BridgeFixture.class.isRecord(), "record metadata");
        check(BridgeFixture.class.getRecordComponents().length == 1
                && BridgeFixture.class.getRecordComponents()[0].getName().equals("value"), "record component");
        var recordMethod = BridgeFixture.class.getDeclaredMethod("calcRecord", int.class);
        check(Modifier.isNative(recordMethod.getModifiers()) == protectedJar, "record method JNI flag");
        var bridge = Arrays.stream(BridgeFixture.class.getDeclaredMethods())
                .filter(method -> method.isBridge() && method.getName().equals("get"))
                .findFirst().orElseThrow();
        check(bridge.isSynthetic() && !Modifier.isNative(bridge.getModifiers()), "synthetic bridge retained");
        var record = new BridgeFixture(12);
        Supplier<?> supplier = record;
        check(supplier.get().equals(12) && bridge.invoke(record).equals(12), "bridge dispatch");
        check(record.calcRecord(41) == 48, "record native result");
        System.out.println("condy=42, bootstrapCalls=1, unicode=75/76, record=48, bridge=12");
    }

    public record BridgeFixture(int value) implements Supplier<Integer> {
        @Override
        public Integer get() {
            return value;
        }

        public int calcRecord(int operand) {
            return operand + 7;
        }
    }

    private static void check(boolean condition, String detail) {
        if (!condition) {
            throw new AssertionError(detail);
        }
    }
}
