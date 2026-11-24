import java.util.Locale;
import java.util.function.IntSupplier;

final class AnalysisWorkload {
    private static final int BATCH = 32;

    static final class Kernel {
        private int state;

        int accumulate(int value) {
            state = state + value;
            return state;
        }

        static int arrayUpdate(int[] values, int index, int delta) {
            int next = values[index] + delta;
            values[index] = next;
            return next;
        }

        static int retainedWithMonitor(int[] values, int index) {
            synchronized (values) {
                try {
                    return values[index];
                } catch (ArrayIndexOutOfBoundsException error) {
                    return -1;
                }
            }
        }

        static int step(int value) {
            return value ^ 0x13271953;
        }

        static int mix(int value, int index) {
            return ((value ^ 0xa37d39b1) * 0x72b3f89d) + (index ^ 0x72a57b21);
        }

        static int batch(int value, int start, int count) {
            for (int offset = 0; offset < count; offset++) {
                int index = start + offset;
                value = ((value ^ 0xa37d39b1) * 0x72b3f89d) + (index ^ 0x72a57b21);
            }
            return value;
        }

        static int conditional(int value) {
            return value < 0 ? value ^ 0x97c364b1 : value + 0x29abf15d;
        }

        static int excluded(int value) {
            return value + 1;
        }
    }

    private static int stepTrial(int seed, int iterations) {
        int value = seed;
        for (int i = 0; i < iterations; i++) value = Kernel.step(value + i);
        return value;
    }

    private static int perCallTrial(int seed, int iterations) {
        int value = seed;
        for (int i = 0; i < iterations; i++) value = Kernel.mix(value, i);
        return value;
    }

    private static int batchedTrial(int seed, int iterations) {
        int value = seed;
        for (int i = 0; i < iterations; i += BATCH) value = Kernel.batch(value, i, BATCH);
        return value;
    }

    private static int probe(int seed) {
        int value = seed;
        int checksum = Kernel.excluded(seed);
        int[] window = new int[16];
        Kernel accumulator = new Kernel();
        for (int i = 0; i < 4_096; i++) {
            value = value * 1_664_525 + 1_013_904_223;
            int expectedBatch = Kernel.mix(value, i);
            int actualBatch = Kernel.batch(value, i, 1);
            if (expectedBatch != actualBatch) throw new AssertionError("batch recurrence differs from mix");
            int updated = Kernel.arrayUpdate(window, i % window.length, expectedBatch);
            int observed = Kernel.retainedWithMonitor(window, i % (window.length + 1));
            checksum = Integer.rotateLeft(checksum, 5) ^ accumulator.accumulate(updated)
                ^ observed ^ actualBatch ^ Kernel.conditional(value) ^ Kernel.step(value);
        }
        return checksum;
    }

    private static void measure(String name, int warmups, int trials, IntSupplier workload) {
        int checksum = workload.getAsInt();
        for (int i = 1; i < warmups; i++) {
            if (workload.getAsInt() != checksum) throw new AssertionError("warmup result changed for " + name);
        }
        for (int i = 0; i < trials; i++) {
            long start = System.nanoTime();
            int result = workload.getAsInt();
            long elapsed = System.nanoTime() - start;
            if (result != checksum) throw new AssertionError("trial result changed for " + name);
            System.out.printf(Locale.ROOT, "%s %d %08x%n", name, elapsed, checksum);
        }
    }

    public static void main(String[] args) {
        String phase = args[0];
        int seed = Integer.parseUnsignedInt(args[1]);
        if (phase.equals("startup")) {
            long start = System.nanoTime();
            int result = Kernel.step(seed);
            long elapsed = System.nanoTime() - start;
            System.out.printf(Locale.ROOT, "startup %d %08x%n", elapsed, result);
            return;
        }
        if (phase.equals("probe")) {
            System.out.printf(Locale.ROOT, "probe 0 %08x%n", probe(seed));
            return;
        }
        if (!phase.equals("steady")) throw new IllegalArgumentException("unknown phase " + phase);
        int iterations = Integer.parseInt(args[2]);
        int warmups = Integer.parseInt(args[3]);
        int trials = Integer.parseInt(args[4]);
        if (iterations < BATCH || iterations % BATCH != 0 || warmups < 1 || trials < 1)
            throw new IllegalArgumentException("invalid measurement arguments");
        measure("step", warmups, trials, () -> stepTrial(seed, iterations));
        measure("per_call", warmups, trials, () -> perCallTrial(seed, iterations));
        measure("batched_32", warmups, trials, () -> batchedTrial(seed, iterations));
    }
}
