import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Deterministic observations under an intentionally unconstrained worker schedule. */
final class ConcurrentCoverage {
    private static final int PUBLICATION_ROUNDS = 512;
    private static final int MONITOR_ROUNDS = 256;
    private static final int WORKERS = 4;
    private static final int REFERENCE_STEPS = 4096;
    private static final int WAIT_SECONDS = 20;

    private static int staticPayload;
    private static volatile int staticStamp;
    private int instancePayload;
    private volatile int instanceStamp;
    private volatile int acknowledged;
    private static int staticCount;
    private int instanceCount;
    private int blockCount;

    private static void covPublishStatic(int stamp, int payload) {
        staticPayload = payload;
        staticStamp = stamp;
    }

    private static long covReadStatic() {
        return ((long) staticStamp << 32) | (staticPayload & 0xffffffffL);
    }

    private void covPublishInstance(int stamp, int payload) {
        instancePayload = payload;
        instanceStamp = stamp;
    }

    private long covReadInstance() {
        return ((long) instanceStamp << 32) | (instancePayload & 0xffffffffL);
    }

    private static synchronized int covStaticMonitor(Throwable failure) throws Throwable {
        if (!Thread.holdsLock(ConcurrentCoverage.class)) return -1;
        int count = ++staticCount;
        if (failure != null) throw failure;
        return count;
    }

    private synchronized int covInstanceMonitor(Throwable failure) throws Throwable {
        if (!Thread.holdsLock(this)) return -1;
        try {
            int count = covReentrantMonitor(failure);
            return Thread.holdsLock(this) ? count : -1;
        } catch (IllegalStateException caught) {
            if (!Thread.holdsLock(this) || caught != failure) return -1;
            throw failure;
        }
    }

    private synchronized int covReentrantMonitor(Throwable failure) throws Throwable {
        if (!Thread.holdsLock(this)) return -1;
        int count = ++instanceCount;
        if (failure != null) throw failure;
        return count;
    }

    private int covBlockMonitor(Object lock, Throwable failure) throws Throwable {
        synchronized (lock) {
            if (!Thread.holdsLock(lock)) return -1;
            int count = ++blockCount;
            if (failure != null) throw failure;
            return count;
        }
    }

    private static int covNullMonitor(Object lock) {
        try {
            synchronized (lock) {
                return 1;
            }
        } catch (NullPointerException failure) {
            return -1;
        }
    }

    private static Object valueOrThrow(Object[] values, int index, Throwable failure) throws Throwable {
        if (index == 0) throw failure;
        return values[index];
    }

    private static Object covReferenceAliases(Object[] values, Throwable failure, int steps) throws Throwable {
        Object current = failure;
        for (int i = 0; i < steps; i++) {
            Object previous = current;
            try {
                current = valueOrThrow(values, i & 3, failure);
            } catch (IllegalStateException caught) {
                if (caught != failure) return null;
                current = caught;
            }
            int index = (i - 1) & 3;
            Object expected = i == 0 || index == 0 ? failure : values[index];
            if (previous != expected) return null;
        }
        return current;
    }

    private void publication() throws InterruptedException {
        runWorkers("volatile publication", deadline -> {
            for (int stamp = 1; stamp <= PUBLICATION_ROUNDS; stamp++) {
                int payload = stamp * 0x9e3779b9;
                covPublishStatic(stamp, payload);
                covPublishInstance(stamp, ~payload);
                // This handshake goes from reader to writer. Publication to the reader
                // depends on the selected methods' volatile stores and loads.
                while (acknowledged != stamp) spinUntil(deadline);
            }
        }, deadline -> {
            for (int stamp = 1; stamp <= PUBLICATION_ROUNDS; stamp++) {
                long expected = ((long) stamp << 32) | ((stamp * 0x9e3779b9) & 0xffffffffL);
                long statik;
                do {
                    statik = covReadStatic();
                    if ((statik >>> 32) < stamp) spinUntil(deadline);
                } while ((statik >>> 32) < stamp);
                long instance;
                do {
                    instance = covReadInstance();
                    if ((instance >>> 32) < stamp) spinUntil(deadline);
                } while ((instance >>> 32) < stamp);
                if (statik != expected || instance != (expected ^ 0xffffffffL))
                    throw new AssertionError("Stale or unexpected publication at round " + stamp);
                acknowledged = stamp;
            }
        });
        System.out.println("concurrent-publication:" + acknowledged);
    }

    private void lockingAndReferences() throws InterruptedException {
        var tasks = new Task[WORKERS];
        var referencesReady = new CountDownLatch(WORKERS);
        for (int worker = 0; worker < tasks.length; worker++) {
            tasks[worker] = deadline -> {
                var failure = new IllegalStateException();
                for (int round = 0; round < MONITOR_ROUNDS; round++) {
                    Throwable expected = (round & 3) == 0 ? failure : null;
                    checkMonitor(ConcurrentCoverage.class, expected, ConcurrentCoverage::covStaticMonitor);
                    checkMonitor(this, expected, this::covInstanceMonitor);
                    checkMonitor(this, expected, thrown -> covBlockMonitor(this, thrown));
                }
                Object[] values = {null, new Object(), new Object(), new Object()};
                referencesReady.countDown();
                if (!referencesReady.await(deadline - System.nanoTime(), TimeUnit.NANOSECONDS))
                    throw new AssertionError("Reference workers did not reach their start barrier");
                if (covReferenceAliases(values, failure, REFERENCE_STEPS) != values[3]
                    || covReferenceAliases(values, failure, REFERENCE_STEPS + 1) != failure)
                    throw new AssertionError("Concurrent reference loop lost an object or exception identity");
            };
        }
        runWorkers("synchronized methods and references", tasks);
        int expected = WORKERS * MONITOR_ROUNDS;
        if (staticCount != expected || instanceCount != expected)
            throw new AssertionError("Lost or repeated synchronized updates");
        if (blockCount != expected || covNullMonitor(null) != -1 || covNullMonitor(this) != 1)
            throw new AssertionError("Explicit monitor count or null behavior changed");
        synchronized (this) {
            try {
                if (covBlockMonitor(this, null) != expected + 1 || !Thread.holdsLock(this))
                    throw new AssertionError("Reentrant explicit monitor released the caller's lock");
            } catch (Throwable failure) {
                throw new AssertionError("Reentrant explicit monitor failed", failure);
            }
        }
        System.out.println("concurrent-block-monitor:" + blockCount);
        System.out.println("concurrent-monitors:" + staticCount + "/" + instanceCount
            + ":throws-per-entry=" + (expected / 4));
        System.out.println("concurrent-reference-iterations:" + WORKERS * (2 * REFERENCE_STEPS + 1));
    }

    @FunctionalInterface
    private interface MonitorCall {
        int invoke(Throwable failure) throws Throwable;
    }

    private static void checkMonitor(Object lock, Throwable expected, MonitorCall call) throws Throwable {
        try {
            if (call.invoke(expected) <= 0)
                throw new AssertionError("Synchronized method ran without its monitor");
            if (expected != null)
                throw new AssertionError("Synchronized method suppressed its exception");
        } catch (IllegalStateException caught) {
            if (caught != expected) throw caught;
        }
        if (Thread.holdsLock(lock))
            throw new AssertionError("Synchronized method retained its monitor");
    }

    @FunctionalInterface
    private interface Task {
        void run(long deadline) throws Throwable;
    }

    private static void runWorkers(String stage, Task... tasks) throws InterruptedException {
        var start = new CountDownLatch(1);
        var finished = new CountDownLatch(tasks.length);
        var failure = new AtomicReference<Throwable>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        for (Task task : tasks) {
            Thread.ofPlatform().daemon().name(stage + " worker").start(() -> {
                try {
                    if (!start.await(WAIT_SECONDS, TimeUnit.SECONDS)) throw new AssertionError("Worker did not start");
                    task.run(deadline);
                } catch (Throwable caught) {
                    failure.compareAndSet(null, caught);
                } finally {
                    finished.countDown();
                }
            });
        }
        start.countDown();
        if (!finished.await(deadline - System.nanoTime(), TimeUnit.NANOSECONDS))
            throw new AssertionError(stage + " did not finish in " + WAIT_SECONDS + " seconds", failure.get());
        if (failure.get() != null) throw new AssertionError(stage + " failed", failure.get());
    }

    private static void spinUntil(long deadline) {
        if (System.nanoTime() - deadline >= 0) throw new AssertionError("Volatile handshake timed out");
        Thread.onSpinWait();
    }

    static void run() throws InterruptedException {
        var stress = new ConcurrentCoverage();
        stress.publication();
        stress.lockingAndReferences();
    }
}
