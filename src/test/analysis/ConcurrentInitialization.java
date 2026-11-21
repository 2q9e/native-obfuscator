import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class ConcurrentInitialization {
    private static final int WAIT_SECONDS = 20;
    private static final CountDownLatch WORKERS_READY = new CountDownLatch(2);
    private static final CountDownLatch START = new CountDownLatch(1);
    private static final CountDownLatch INITIALIZERS_ACTIVE = new CountDownLatch(2);
    private static final StringBuilder[] EVENTS = {new StringBuilder(), new StringBuilder()};
    private static final String EXPECTED_EVENTS = "enter,int,overlap,long,exit,";

    private ConcurrentInitialization() {}

    public static void main(String[] args) throws Exception {
        var workers = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory());
        try {
            var left = workers.submit(starting(ConcurrentInitialization::leftValues));
            var right = workers.submit(starting(ConcurrentInitialization::rightValues));
            await(WORKERS_READY, "both workers to start");
            if (EVENTS[0].length() != 0 || EVENTS[1].length() != 0)
                throw new AssertionError("Owner initialized before its worker was released");
            START.countDown();
            String leftValues = left.get(WAIT_SECONDS, TimeUnit.SECONDS);
            String rightValues = right.get(WAIT_SECONDS, TimeUnit.SECONDS);
            printResult("left", EVENTS[0], leftValues);
            printResult("right", EVENTS[1], rightValues);
        } finally {
            workers.shutdownNow();
        }
    }

    private static Callable<String> starting(Callable<String> call) {
        return () -> {
            WORKERS_READY.countDown();
            await(START, "main thread to release workers");
            return call.call();
        };
    }

    private static String leftValues() {
        int first = ConcurrentLeft.calcStatic(Integer.MIN_VALUE);
        long second = ConcurrentLeft.calcStatic(Long.MIN_VALUE);
        var owner = new ConcurrentLeft(-37);
        return ConcurrentLeft.INIT_INT + "," + ConcurrentLeft.INIT_LONG + "," + first + "," + second
            + "," + owner.calcInstance(-199) + "," + owner.calcInstance(-0x102030405060708L);
    }

    private static String rightValues() {
        int first = ConcurrentRight.calcStatic(Integer.MAX_VALUE);
        long second = ConcurrentRight.calcStatic(Long.MAX_VALUE);
        var owner = new ConcurrentRight(73);
        return ConcurrentRight.INIT_INT + "," + ConcurrentRight.INIT_LONG + "," + first + "," + second
            + "," + owner.calcInstance(-211) + "," + owner.calcInstance(0x7010203040506070L);
    }

    static void record(int owner, String event) {
        EVENTS[owner].append(event).append(',');
    }

    static void overlap(int owner) {
        INITIALIZERS_ACTIVE.countDown();
        try {
            await(INITIALIZERS_ACTIVE, "both owner initializers to reach their first native candidate");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Owner initialization interrupted", interrupted);
        }
        record(owner, "overlap");
    }

    private static void await(CountDownLatch latch, String condition) throws InterruptedException {
        if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS))
            throw new AssertionError("Timed out waiting for " + condition);
    }

    private static void printResult(String owner, StringBuilder events, String values) {
        if (!EXPECTED_EVENTS.contentEquals(events))
            throw new AssertionError("Unexpected " + owner + " initialization sequence " + events);
        System.out.println(owner + " " + events + " " + values);
    }
}

final class ConcurrentLeft {
    static final int INIT_INT;
    static final long INIT_LONG;
    private static int phase;
    private final int bias;

    static {
        ConcurrentInitialization.record(0, "enter");
        phase = 7;
        INIT_INT = calcStatic(0x81234567);
        ConcurrentInitialization.record(0, "int");
        ConcurrentInitialization.overlap(0);
        phase = -11;
        INIT_LONG = calcStatic(0x123456789abcdef0L);
        ConcurrentInitialization.record(0, "long");
        phase = 23;
        ConcurrentInitialization.record(0, "exit");
    }

    ConcurrentLeft(int bias) {
        this.bias = bias;
    }

    static int calcStatic(int value) {
        return (value ^ 0x13579bdf) + phase;
    }

    static long calcStatic(long value) {
        return (value << 5) ^ (phase * 17L);
    }

    int calcInstance(int value) {
        return (value * 3) - bias;
    }

    long calcInstance(long value) {
        return (value >>> 7) ^ bias;
    }
}

final class ConcurrentRight {
    static final int INIT_INT;
    static final long INIT_LONG;
    private static int phase;
    private final int bias;

    static {
        ConcurrentInitialization.record(1, "enter");
        phase = -13;
        INIT_INT = calcStatic(0x92345678);
        ConcurrentInitialization.record(1, "int");
        ConcurrentInitialization.overlap(1);
        phase = 19;
        INIT_LONG = calcStatic(-0x2233445566778899L);
        ConcurrentInitialization.record(1, "long");
        phase = -29;
        ConcurrentInitialization.record(1, "exit");
    }

    ConcurrentRight(int bias) {
        this.bias = bias;
    }

    static int calcStatic(int value) {
        return (value << 3) ^ (phase * 5);
    }

    static long calcStatic(long value) {
        return (value - 0x1122334455667788L) * phase;
    }

    int calcInstance(int value) {
        return (value >>> 2) + bias;
    }

    long calcInstance(long value) {
        return (value * 13L) + bias;
    }
}
