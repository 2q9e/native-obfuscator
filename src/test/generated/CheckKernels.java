import java.util.Random;

public final class CheckKernels {
    public static void main(String[] args) throws Throwable {
        Class<?> kernels = Class.forName("GeneratedKernels");
        var random = new Random(24680L);
        boolean expectNative = args[0].equals("native");
        long checksum = 0;
        for (int i = 0; i < 60; i++) {
            var method = kernels.getMethod("calc" + i, long.class, long.class, int.class);
            if (java.lang.reflect.Modifier.isNative(method.getModifiers()) != expectNative)
                throw new AssertionError("Unexpected implementation for " + method);
            for (int value = 0; value < 1000; value++) {
                long result = (long) method.invoke(null, random.nextLong(), random.nextLong(), random.nextInt());
                checksum = Long.rotateLeft(checksum, 9) ^ result;
            }
        }
        System.out.printf("%016x%n", checksum);
    }
}
