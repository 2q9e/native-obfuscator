import java.util.Arrays;

public final class Workload {
    private static final int ITERATIONS = 750_000;
    private static final int TRIALS = 9;

    static int boundary(int value) {
        return value ^ 0x13271953;
    }

    static int mix(int x, int y) {
        for (int round = 0; round < 12; round++) {
            x += y ^ (x << 13);
            y -= x ^ (y >>> 7);
            x = (x >>> 11) ^ (y << 9);
        }
        return x ^ y;
    }

    private static int boundaryTrial() {
        int value = 637;
        for (int i = 0; i < ITERATIONS; i++) value = boundary(value + i);
        return value;
    }

    private static int arithmeticTrial() {
        int value = 637;
        for (int i = 0; i < ITERATIONS; i++) value = mix(value, i);
        return value;
    }

    private static void measure(String name, java.util.function.IntSupplier trial) {
        for (int i = 0; i < 5; i++) trial.getAsInt();
        var samples = new long[TRIALS];
        int checksum = 0;
        for (int i = 0; i < TRIALS; i++) {
            long start = System.nanoTime();
            checksum ^= trial.getAsInt();
            samples[i] = System.nanoTime() - start;
        }
        Arrays.sort(samples);
        System.out.printf(java.util.Locale.ROOT, "%s %.3f %08x%n",
            name, (double) samples[TRIALS / 2] / ITERATIONS, checksum);
    }

    public static void main(String[] args) {
        measure("boundary", Workload::boundaryTrial);
        measure("arithmetic", Workload::arithmeticTrial);
    }
}
