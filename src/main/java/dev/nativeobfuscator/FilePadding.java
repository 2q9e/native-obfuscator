package dev.nativeobfuscator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;

final class FilePadding {
    private static final List<Class<?>> UTILITIES = List.of(
        SortedIntSearch.class, PrefixSums.class, ByteHistogram.class, RollingChecksum.class,
        BitCount.class, TextDistance.class, RunLengths.class, MovingAverage.class,
        DotProduct.class, CoordinatePacking.class, EuclideanMath.class, ByteSequence.class
    );

    private FilePadding() {}

    static int classCount() {
        return UTILITIES.size();
    }

    static void bundle(Map<String, byte[]> files, String resourceRoot, String buildId) throws IOException {
        for (Class<?> utility : UTILITIES) {
            String original = Type.getInternalName(utility);
            String renamed = "dev/nativeobfuscator/padding/C" + identifier(buildId, original);
            var names = new HashMap<String, String>();
            names.put(original, renamed);
            for (var method : utility.getDeclaredMethods()) {
                String methodKey = original + "." + method.getName() + Type.getMethodDescriptor(method);
                names.put(methodKey, "m" + identifier(buildId, methodKey));
            }

            var writer = new ClassWriter(0);
            try (var input = utility.getResourceAsStream("/" + original + ".class")) {
                if (input == null) throw new IOException("Tool JAR is missing a padding class template: " + original);
                new ClassReader(input).accept(
                    new ClassRemapper(writer, new SimpleRemapper(Opcodes.ASM9, names)), ClassReader.SKIP_DEBUG);
            }
            String path = resourceRoot + "padding/" + renamed + ".class";
            if (files.putIfAbsent(path, writer.toByteArray()) != null)
                throw new IOException("Padding class path collides with a JAR entry: " + path);
        }
    }

    private static String identifier(String buildId, String symbol) {
        byte[] hash = Archive.sha256().digest((buildId + "/" + symbol).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash, 0, 12);
    }
}

final class SortedIntSearch {
    static int lowerBound(int[] values, int key) {
        int from = 0;
        int to = values.length;
        while (from < to) {
            int middle = from + (to - from) / 2;
            if (values[middle] < key) from = middle + 1;
            else to = middle;
        }
        return from;
    }
}

final class PrefixSums {
    static long[] inclusive(int[] values) {
        long[] sums = new long[values.length];
        long sum = 0;
        for (int i = 0; i < values.length; i++) {
            sum += values[i];
            sums[i] = sum;
        }
        return sums;
    }
}

final class ByteHistogram {
    static int[] count(byte[] values) {
        int[] counts = new int[256];
        for (byte value : values) counts[Byte.toUnsignedInt(value)]++;
        return counts;
    }
}

final class RollingChecksum {
    static long adler32(byte[] values) {
        int low = 1;
        int high = 0;
        for (byte value : values) {
            low = (low + Byte.toUnsignedInt(value)) % 65521;
            high = (high + low) % 65521;
        }
        return ((long) high << 16) | low;
    }
}

final class BitCount {
    static int population(long value) {
        int count = 0;
        while (value != 0) {
            value &= value - 1;
            count++;
        }
        return count;
    }
}

final class TextDistance {
    static int between(String left, String right) {
        int[] row = new int[right.length() + 1];
        for (int j = 0; j < row.length; j++) row[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            int previous = row[0];
            row[0] = i;
            for (int j = 1; j < row.length; j++) {
                int substitution = previous + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1);
                previous = row[j];
                row[j] = Math.min(substitution, 1 + Math.min(row[j - 1], row[j]));
            }
        }
        return row[right.length()];
    }
}

final class RunLengths {
    static long[] encode(int[] values) {
        long[] runs = new long[values.length];
        int count = 0;
        for (int start = 0; start < values.length;) {
            int end = start + 1;
            while (end < values.length && values[start] == values[end]) end++;
            runs[count++] = ((long) values[start] << 32) | (end - start);
            start = end;
        }
        return Arrays.copyOf(runs, count);
    }
}

final class MovingAverage {
    static double[] calculate(int[] values, int width) {
        if (width < 1 || width > values.length) throw new IllegalArgumentException("Invalid window width");
        double[] averages = new double[values.length - width + 1];
        long sum = 0;
        for (int i = 0; i < values.length; i++) {
            sum += values[i];
            if (i >= width) sum -= values[i - width];
            if (i + 1 >= width) averages[i + 1 - width] = (double) sum / width;
        }
        return averages;
    }
}

final class DotProduct {
    static long sum(int[] left, int[] right) {
        if (left.length != right.length) throw new IllegalArgumentException("Vector sizes differ");
        long result = 0;
        for (int i = 0; i < left.length; i++) {
            result = Math.addExact(result, (long) left[i] * right[i]);
        }
        return result;
    }
}

final class CoordinatePacking {
    static long pack(int x, int y) {
        return ((long) x << 32) | Integer.toUnsignedLong(y);
    }

    static int unpackX(long value) {
        return (int) (value >> 32);
    }

    static int unpackY(long value) {
        return (int) value;
    }
}

final class EuclideanMath {
    static long gcd(int left, int right) {
        long a = Math.abs((long) left);
        long b = Math.abs((long) right);
        while (b != 0) {
            long remainder = a % b;
            a = b;
            b = remainder;
        }
        return a;
    }
}

final class ByteSequence {
    static int indexOf(byte[] values, byte[] pattern) {
        for (int start = 0; start <= values.length - pattern.length; start++) {
            int offset = 0;
            while (offset < pattern.length && values[start + offset] == pattern[offset]) offset++;
            if (offset == pattern.length) return start;
        }
        return -1;
    }
}
