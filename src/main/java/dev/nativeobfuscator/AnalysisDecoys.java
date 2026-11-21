package dev.nativeobfuscator;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;

import static org.objectweb.asm.Opcodes.*;

/** Synthetic utility classes with one entry each for the ordinary JNI conversion pipeline. */
final class AnalysisDecoys {
    static final int MAX_METHODS = 16;

    private static final long SEED_DOMAIN = 0x768934dca273ad81L;
    private static final String VERSION_PREFIX = "META-INF/versions/";
    private static final String CLASS_SUFFIX = ".class";
    private static final Pattern PACKAGE = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*/)+");

    private AnalysisDecoys() {}

    /**
     * Entries describe the original archive. The overload accepts additional class paths to reserve
     * without including those generated classes in package selection.
     */
    static FilePadding.Generation generate(Map<String, byte[]> existing, int requestedMethods, long analysisSeed)
            throws IOException {
        return generate(existing, requestedMethods, analysisSeed, Set.of());
    }

    static FilePadding.Generation generate(Map<String, byte[]> existing, int requestedMethods, long analysisSeed,
                                           Set<String> reservedClassPaths) throws IOException {
        if (requestedMethods < 0 || requestedMethods > MAX_METHODS)
            throw new IllegalArgumentException("Analysis utility method count must be between 0 and " + MAX_METHODS);
        if (requestedMethods == 0) return new FilePadding.Generation(Map.of(), Map.of());

        var occupied = new HashSet<>(FabricExclusions.from(existing).keySet());
        for (String path : reservedClassPaths) reservePath(path, occupied);
        var packages = new TreeMap<String, Integer>();
        for (var entry : existing.entrySet()) {
            String path = entry.getKey();
            if (!path.endsWith(CLASS_SUFFIX)) continue;
            reservePath(path, occupied);
            try {
                var reader = new ClassReader(entry.getValue());
                String owner = reader.getClassName();
                occupied.add(owner);
                if (path.startsWith("META-INF/") || !path.equals(owner + CLASS_SUFFIX)
                    || (reader.getAccess() & (ACC_INTERFACE | ACC_ANNOTATION | ACC_MODULE)) != 0) continue;
                int separator = owner.lastIndexOf('/');
                String leaf = owner.substring(separator + 1);
                if (leaf.equals("package-info") || leaf.equals("module-info")) continue;
                String pkg = owner.substring(0, separator + 1);
                if (safePackage(pkg)) packages.merge(pkg, reader.readUnsignedShort(6), Math::min);
            } catch (RuntimeException failure) {
                // Future or invalid classes under metadata can still reserve their versioned path.
                if (path.startsWith("META-INF/")) continue;
                throw new IOException("Cannot inspect analysis utility class reference " + path, failure);
            }
        }
        if (packages.size() > 1) packages.remove("");
        if (packages.isEmpty()) packages.put("utility/", V1_8);
        var candidates = new ArrayList<>(packages.entrySet());

        var random = new SplittableRandom(analysisSeed ^ SEED_DOMAIN);
        var schedule = new ArrayList<>(List.of(Utility.values()));
        Collections.shuffle(schedule, random);
        var classes = new LinkedHashMap<String, byte[]>();
        var selected = new LinkedHashMap<String, String>();
        for (int index = 0; index < requestedMethods; index++) {
            Utility utility = schedule.get(index);
            var location = candidates.get(random.nextInt(candidates.size()));
            String primary = utility.template.getSimpleName();
            String alternate = utility.alternate;
            if (random.nextBoolean()) {
                primary = utility.alternate;
                alternate = utility.template.getSimpleName();
            }
            String owner = availableOwner(location.getKey(), primary, alternate, occupied);
            String source = Type.getInternalName(utility.template);
            var node = readTemplate(source);
            var entry = node.methods.stream().filter(method -> method.name.equals(utility.entryPoint)
                && (method.access & ACC_PUBLIC) != 0).findFirst().orElseThrow(
                    () -> new IOException("Analysis utility is missing its public entry point " + utility.entryPoint));
            node.version = Math.clamp(location.getValue(), V1_8, node.version);
            node.access = ACC_PUBLIC | ACC_FINAL | ACC_SUPER;
            node.nestHostClass = null;
            node.innerClasses.clear();
            var writer = new ClassWriter(0);
            node.accept(new ClassRemapper(writer, new SimpleRemapper(ASM9, Map.of(source, owner))));
            classes.put(owner + CLASS_SUFFIX, writer.toByteArray());
            selected.put(owner + "#" + entry.name + entry.desc, utility.name().toLowerCase(Locale.ROOT).replace('_', '-'));
        }
        return new FilePadding.Generation(Collections.unmodifiableMap(classes), Collections.unmodifiableMap(selected));
    }

    private static ClassNode readTemplate(String owner) throws IOException {
        try (InputStream input = AnalysisDecoys.class.getResourceAsStream("/" + owner + CLASS_SUFFIX)) {
            if (input == null) throw new IOException("Tool JAR is missing analysis utility template " + owner);
            var node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG);
            return node;
        }
    }

    private static void reservePath(String path, Set<String> occupied) {
        if (!path.endsWith(CLASS_SUFFIX)) return;
        int component = path.startsWith(VERSION_PREFIX) ? path.indexOf('/', VERSION_PREFIX.length()) + 1 : 0;
        occupied.add(path.substring(component, path.length() - CLASS_SUFFIX.length()));
    }

    private static boolean safePackage(String name) {
        return name.isEmpty() || (name.length() <= 1024 && PACKAGE.matcher(name).matches()
            && !name.startsWith("java/") && !name.startsWith("javax/") && !name.startsWith("jdk/")
            && !name.startsWith("sun/") && !name.startsWith("com/sun/")
            && !name.startsWith("dev/nativeobfuscator/runtime/"));
    }

    private static String availableOwner(String pkg, String primary, String alternate, Set<String> occupied) {
        if (occupied.add(pkg + primary)) return pkg + primary;
        if (occupied.add(pkg + alternate)) return pkg + alternate;
        int suffix = 2;
        while (!occupied.add(pkg + primary + suffix)) suffix++;
        return pkg + primary + suffix;
    }

    private enum Utility {
        WEIGHTED_MEAN(WindowAverage.class, "WeightedWindow", "weightedMean"),
        SAMPLE_VARIANCE(SampleMoments.class, "SampleVariance", "variance"),
        LOWER_BOUND(SortedBounds.class, "TimelineSearch", "lowerBound"),
        LONGEST_RUN(TimelineRuns.class, "SampleRuns", "longestRun"),
        SATURATING_ADD(SaturatingSum.class, "BoundedArithmetic", "addBounded"),
        INTERVAL_COVERAGE(IntervalCoverage.class, "SegmentMeasure", "coverage"),
        PREFIX_TOTALS(DeltaSeries.class, "RunningTotals", "prefixTotals"),
        WINDOW_COPY(ByteWindow.class, "BufferTransfer", "copyWindow"),
        CURSOR_TRANSITION(RateCursor.class, "PositionCursor", "move"),
        MATRIX_INDEX(MatrixAddress.class, "GridOffset", "index"),
        LINEAR_INTERPOLATION(RateEstimator.class, "RateInterpolation", "interpolate"),
        PAGE_ALIGNMENT(PageAlignment.class, "CapacityAlignment", "align"),
        BUCKET_COUNTS(BatchBins.class, "BucketTally", "countInto"),
        MONOTONIC_SEQUENCE(MonotonicSeries.class, "SequenceOrder", "isNondecreasing"),
        TRIMMED_SUM(TrimmedValues.class, "TrimmedStatistics", "middleSum"),
        THRESHOLD_CROSSINGS(SegmentQuery.class, "ThresholdSeries", "countCrossings");

        final Class<?> template;
        final String alternate;
        final String entryPoint;

        Utility(Class<?> template, String alternate, String entryPoint) {
            this.template = template;
            this.alternate = alternate;
            this.entryPoint = entryPoint;
        }
    }

    // Templates compile as ordinary Java and are relocated individually without debug or nest metadata.

    private static final class WindowAverage {
        // Ignore nonpositive weights and clamp each positive weight to 32767.
        public static long weightedMean(int[] values, int[] weights) {
            if (values == null || weights == null) return 0;
            long weighted = 0;
            int total = 0;
            for (int i = 0; i < 32 && i < values.length && i < weights.length; i++) {
                int weight = weights[i];
                if (weight <= 0) continue;
                if (weight > 32767) weight = 32767;
                weighted += (long) values[i] * weight;
                total += weight;
            }
            return total == 0 ? 0 : weighted / total;
        }
    }

    private static final class SampleMoments {
        // Welford's recurrence gives the population variance without a sum-of-squares overflow.
        public static double variance(short[] values, int limit) {
            if (values == null) return 0;
            double mean = 0;
            double deviation = 0;
            int count = 0;
            while (count < 96 && count < values.length && count < limit) {
                double delta = values[count] - mean;
                count++;
                mean += delta / count;
                deviation += delta * (values[count - 1] - mean);
            }
            return count == 0 ? 0 : deviation / count;
        }
    }

    private static final class SortedBounds {
        // Find the first value >= target in an ascending prefix.
        public static int lowerBound(long[] points, long target) {
            if (points == null) return 0;
            int left = 0;
            int right = points.length > 4096 ? 4096 : points.length;
            while (left < right) {
                int middle = left + ((right - left) >>> 1);
                if (points[middle] < target) left = middle + 1;
                else right = middle;
            }
            return left;
        }
    }

    private static final class TimelineRuns {
        public static int longestRun(byte[] flags, byte value) {
            if (flags == null) return 0;
            int best = 0;
            int run = 0;
            for (int i = 0; i < 256 && i < flags.length; i++) {
                if (flags[i] != value) run = 0;
                else if (++run > best) best = run;
            }
            return best;
        }
    }

    private static final class SaturatingSum {
        public static long addBounded(long floor, long ceiling, long value, int delta) {
            if (floor > ceiling) return floor;
            if (delta > 0 && value > Long.MAX_VALUE - delta) return ceiling;
            if (delta < 0 && value < Long.MIN_VALUE - delta) return floor;
            return constrain(value + delta, floor, ceiling);
        }

        private static long constrain(long value, long floor, long ceiling) {
            if (value < floor) return floor;
            if (value > ceiling) return ceiling;
            return value;
        }
    }

    private static final class IntervalCoverage {
        // Intervals have ascending starts and exclusive ends.
        public static long coverage(int[] starts, int[] ends) {
            if (starts == null || ends == null) return 0;
            long covered = 0;
            int previousStart = Integer.MIN_VALUE;
            int lastEnd = Integer.MIN_VALUE;
            for (int i = 0; i < 64 && i < starts.length && i < ends.length; i++) {
                int start = starts[i];
                int end = ends[i];
                if (start < previousStart || end < start) return -1;
                previousStart = start;
                if (start == end || end <= lastEnd) continue;
                int firstUncovered = start < lastEnd ? lastEnd : start;
                covered += (long) end - firstUncovered;
                lastEnd = end;
            }
            return covered;
        }
    }

    private static final class DeltaSeries {
        public static int prefixTotals(short[] steps, int[] totals, int initial) {
            if (steps == null || totals == null) return 0;
            int i = 0;
            while (i < 80 && i < steps.length && i < totals.length) {
                initial = narrow((long) initial + steps[i]);
                totals[i] = initial;
                i++;
            }
            return i;
        }

        private static int narrow(long sum) {
            if (sum > Integer.MAX_VALUE) return Integer.MAX_VALUE;
            if (sum < Integer.MIN_VALUE) return Integer.MIN_VALUE;
            return (int) sum;
        }
    }

    private static final class ByteWindow {
        // Copy at most 128 bytes and preserve overlapping moves within the same buffer.
        public static int copyWindow(byte[] source, int sourceOffset, byte[] target, int targetOffset, int requested) {
            if (source == null || target == null || sourceOffset < 0 || targetOffset < 0 || requested <= 0) return 0;
            int count = requested > 128 ? 128 : requested;
            if (count > source.length - sourceOffset) count = source.length - sourceOffset;
            if (count > target.length - targetOffset) count = target.length - targetOffset;
            if (count <= 0) return 0;
            if (source == target && targetOffset > sourceOffset && targetOffset - sourceOffset < count) {
                for (int i = count - 1; i >= 0; i--) target[targetOffset + i] = source[sourceOffset + i];
            } else {
                for (int i = 0; i < count; i++) target[targetOffset + i] = source[sourceOffset + i];
            }
            return count;
        }
    }

    private static final class RateCursor {
        private final int limit;
        private int position;

        public RateCursor(int limit) {
            this.limit = limit < 0 ? 0 : limit;
        }

        public int position() {
            return position;
        }

        public int move(int value, int mode) {
            long next;
            switch (mode) {
                case 0:
                    next = value;
                    break;
                case 1:
                    next = (long) position + value;
                    break;
                case 2:
                    next = ((long) position + value) % ((long) limit + 1);
                    if (next < 0) next += (long) limit + 1;
                    break;
                default:
                    return position;
            }
            if (next < 0) next = 0;
            if (next > limit) next = limit;
            position = (int) next;
            return position;
        }
    }

    private static final class MatrixAddress {
        public static long index(int row, int column, int rows, int columns) {
            if (row < 0 || row >= rows || column < 0 || column >= columns) return -1;
            return (long) row * columns + column;
        }
    }

    private static final class RateEstimator {
        // Interpolate between ascending time marks and extend the nearest endpoint otherwise.
        public static float interpolate(int[] marks, float[] rates, int time) {
            if (marks == null || rates == null || marks.length == 0 || rates.length == 0) return Float.NaN;
            int previous = marks[0];
            float rate = rates[0];
            if (time <= previous) return rate;
            for (int i = 1; i < 32 && i < marks.length && i < rates.length; i++) {
                int next = marks[i];
                if (next <= previous) return Float.NaN;
                float nextRate = rates[i];
                if (time < next) {
                    double fraction = ((long) time - previous) / (double) ((long) next - previous);
                    return (float) (rate + (nextRate - (double) rate) * fraction);
                }
                if (time == next) return nextRate;
                previous = next;
                rate = nextRate;
            }
            return rate;
        }
    }

    private static final class PageAlignment {
        public static long align(long size, int alignment, long ceiling) {
            if (size < 0 || size > ceiling || alignment <= 0) return -1;
            long remainder = size % alignment;
            if (remainder == 0) return size;
            long padding = alignment - remainder;
            return padding > ceiling - size ? -1 : size + padding;
        }
    }

    private static final class BatchBins {
        // Add unsigned byte samples to existing, nonnegative saturating bins.
        public static int countInto(byte[] values, short[] buckets) {
            if (values == null || buckets == null) return 0;
            int counted = 0;
            for (int i = 0; i < 192 && i < values.length; i++) {
                int bucket = values[i] & 255;
                if (bucket >= buckets.length) continue;
                short tally = buckets[bucket];
                if (tally < 0 || tally == Short.MAX_VALUE) continue;
                buckets[bucket] = (short) (tally + 1);
                counted++;
            }
            return counted;
        }
    }

    private static final class MonotonicSeries {
        // Validate a complete prefix within the supported window.
        public static boolean isNondecreasing(char[] points, int length) {
            if (points == null || length < 0 || length > points.length || length > 64) return false;
            for (int i = 1; i < length; i++) {
                if (points[i - 1] > points[i]) return false;
            }
            return true;
        }
    }

    private static final class TrimmedValues {
        public static long middleSum(int[] values, int start, int limit) {
            if (values == null || start < 0 || start >= values.length || limit < 3) return 0;
            int count = values.length - start;
            if (count > limit) count = limit;
            if (count > 48) count = 48;
            if (count < 3) return 0;

            int minimum = values[start];
            int maximum = minimum;
            long sum = minimum;
            for (int i = 1; i < count; i++) {
                int value = values[start + i];
                if (value < minimum) minimum = value;
                else if (value > maximum) maximum = value;
                sum += value;
            }
            return sum - minimum - maximum;
        }
    }

    private static final class SegmentQuery {
        // A missing measurement breaks continuity between samples.
        public static int countCrossings(double[] measurements, double threshold) {
            if (measurements == null || threshold != threshold) return 0;
            int previous = 0;
            int crossings = 0;
            for (int i = 0; i < 72 && i < measurements.length; i++) {
                double value = measurements[i];
                if (value != value) {
                    previous = 0;
                    continue;
                }
                int side = value < threshold ? -1 : 1;
                if (previous != 0 && side != previous) crossings++;
                previous = side;
            }
            return crossings;
        }
    }
}
