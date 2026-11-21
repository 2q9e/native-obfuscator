import dev.nativeobfuscator.runtime.AnalysisManifest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class AnalysisManifestTest {
    private static final int HEADER_SIZE = 18;
    private static final int VERSION_OFFSET = 4;
    private static final int PROFILE_OFFSET = 5;
    private static final int BYTE_BUDGET_OFFSET = 6;
    private static final int ENTRY_BUDGET_OFFSET = 10;
    private static final int ENTRY_COUNT_OFFSET = 14;
    private static final String FIRST_PATH = "analysis/a.txt";
    private static final String SECOND_PATH = "analysis/data/file-2.tsv";
    private static final byte[] VALUE = {42};

    private AnalysisManifestTest() {}

    public static void main(String[] args) throws Exception {
        canonicalFormat();
        budgetBoundaries();
        malformedManifests();
        resourceValidation();
        runtimeFailure();
        System.out.println("AnalysisManifestTest passed");
    }

    private static void canonicalFormat() throws Exception {
        byte[] secondValue = {3, 5, 7};
        Map<String, byte[]> corpus = new LinkedHashMap<>();
        corpus.put(SECOND_PATH, secondValue);
        corpus.put(FIRST_PATH, VALUE);
        int byteBudget = AnalysisManifest.encodedSize(corpus.keySet()) + VALUE.length + secondValue.length;
        byte[] encoded = AnalysisManifest.encode(1, byteBudget, 3, corpus);
        check(encoded.length == AnalysisManifest.encodedSize(corpus.keySet()), "exact encoded size");
        Map<String, byte[]> forward = new LinkedHashMap<>();
        forward.put(FIRST_PATH, VALUE);
        forward.put(SECOND_PATH, secondValue);
        check(Arrays.equals(encoded, AnalysisManifest.encode(1, byteBudget, 3, forward)), "stable sorted bytes");

        try (DataInputStream wire = new DataInputStream(new ByteArrayInputStream(encoded))) {
            check(wire.readInt() == 0x4e4f414d, "wire magic");
            check(wire.readUnsignedByte() == 1, "wire version");
            check(wire.readUnsignedByte() == 1, "wire profile");
            check(wire.readInt() == byteBudget, "wire byte budget");
            check(wire.readInt() == 3, "wire entry budget");
            check(wire.readInt() == 2, "wire corpus count");
            checkRecord(wire, FIRST_PATH, VALUE);
            checkRecord(wire, SECOND_PATH, secondValue);
            check(wire.read() == -1, "exact wire termination");
        }

        TrackingStream manifest = new TrackingStream(encoded);
        Map<String, TrackingStream> streams = Map.of(FIRST_PATH, new TrackingStream(VALUE),
            SECOND_PATH, new TrackingStream(secondValue));
        List<String> accessed = new ArrayList<>();
        byte[] actualDigest = AnalysisManifest.validate(manifest, path -> {
            accessed.add(path);
            return streams.get(path);
        });
        check(Arrays.equals(actualDigest, sha256(encoded)), "digest covers exact manifest bytes");
        check(accessed.equals(List.of(FIRST_PATH, SECOND_PATH)), "canonical lookup order");
        check(manifest.closed && streams.values().stream().allMatch(stream -> stream.closed), "streams closed");
    }

    private static void budgetBoundaries() throws Exception {
        byte[] maximumEntry = new byte[AnalysisManifest.MAX_ENTRY_BYTES];
        Map<String, byte[]> corpus = Map.of(FIRST_PATH, maximumEntry);
        int budget = AnalysisManifest.encodedSize(corpus.keySet()) + maximumEntry.length;
        byte[] encoded = AnalysisManifest.encode(1, budget, 2, corpus);
        valid(encoded, corpus);
        expectIOException(() -> AnalysisManifest.encode(1, budget - 1, 2, corpus), "manifest counted in byte budget");
        expectIOException(() -> AnalysisManifest.encode(1, budget, 1, corpus), "manifest counted in entry budget");
        expectIOException(() -> AnalysisManifest.encode(1, AnalysisManifest.BALANCED_BYTES + 1, 2, corpus),
            "balanced byte ceiling");
        expectIOException(() -> AnalysisManifest.encode(2, AnalysisManifest.MAXIMUM_BYTES + 1, 2, corpus),
            "maximum byte ceiling");
        expectIOException(() -> AnalysisManifest.encode(1, budget, AnalysisManifest.BALANCED_ENTRIES + 1, corpus),
            "balanced entry ceiling");
        expectIOException(() -> AnalysisManifest.encode(2, budget, AnalysisManifest.MAXIMUM_ENTRIES + 1, corpus),
            "maximum entry ceiling");
        expectIOException(() -> AnalysisManifest.encode(1, budget, 2, Map.of()), "empty corpus");
        expectIOException(() -> AnalysisManifest.encode(1, budget, 2, Map.of(FIRST_PATH, new byte[0])),
            "empty resource");
        expectIOException(() -> AnalysisManifest.encode(1, budget + 1, 2,
            Map.of(FIRST_PATH, new byte[maximumEntry.length + 1])), "oversize resource");

        Map<String, byte[]> balanced = corpus(AnalysisManifest.BALANCED_ENTRIES - 1);
        valid(AnalysisManifest.encode(1, AnalysisManifest.BALANCED_BYTES, AnalysisManifest.BALANCED_ENTRIES,
            balanced), balanced);
        Map<String, byte[]> maximum = corpus(AnalysisManifest.MAXIMUM_ENTRIES - 1);
        valid(AnalysisManifest.encode(2, AnalysisManifest.MAXIMUM_BYTES, AnalysisManifest.MAXIMUM_ENTRIES,
            maximum), maximum);

        String maxPath = AnalysisManifest.PREFIX + "a".repeat(244) + ".a";
        Map<String, byte[]> maxPathCorpus = Map.of(maxPath, VALUE);
        valid(AnalysisManifest.encode(1, 1024, 2, maxPathCorpus), maxPathCorpus);
        expectIOException(() -> AnalysisManifest.encode(1, 1024, 2, Map.of(maxPath + "a", VALUE)),
            "path over 255 bytes");
    }

    private static void malformedManifests() throws Exception {
        byte[] valid = AnalysisManifest.encode(1, 1024, 2, Map.of(FIRST_PATH, VALUE));
        for (int length = 0; length < valid.length; length++) {
            malformed(Arrays.copyOf(valid, length), "truncated at " + length);
        }
        malformed(mutatedByte(valid, 0, 0), "bad magic");
        malformed(mutatedByte(valid, VERSION_OFFSET, 2), "unsupported version");
        malformed(mutatedByte(valid, PROFILE_OFFSET, 0), "unsupported profile");
        malformed(mutatedInt(valid, BYTE_BUDGET_OFFSET, 0), "zero byte budget");
        malformed(mutatedInt(valid, BYTE_BUDGET_OFFSET, Integer.MAX_VALUE), "oversize byte budget");
        malformed(mutatedInt(valid, BYTE_BUDGET_OFFSET, valid.length), "resource outside byte budget");
        malformed(mutatedInt(valid, ENTRY_BUDGET_OFFSET, 1), "manifest and corpus outside entry budget");
        malformed(mutatedInt(valid, ENTRY_COUNT_OFFSET, 0), "empty entry list");
        malformed(mutatedInt(valid, ENTRY_COUNT_OFFSET, Integer.MIN_VALUE), "negative entry count");
        malformed(mutatedInt(valid, ENTRY_COUNT_OFFSET, Integer.MAX_VALUE), "oversize entry count");
        malformed(mutatedByte(valid, HEADER_SIZE, 0), "zero path length");
        malformed(mutatedInt(valid, HEADER_SIZE + 1 + FIRST_PATH.length(), 0), "zero resource length");
        malformed(mutatedInt(valid, HEADER_SIZE + 1 + FIRST_PATH.length(), Integer.MAX_VALUE),
            "oversize resource length");
        malformed(Arrays.copyOf(valid, valid.length + 1), "trailing data");
        malformed(rawManifest(List.of(FIRST_PATH, FIRST_PATH)), "duplicate resource");
        malformed(rawManifest(List.of(SECOND_PATH, FIRST_PATH)), "unsorted resource");

        for (String path : List.of(AnalysisManifest.PATH, "elsewhere/a.txt", "analysis/../a.txt",
            "analysis/a//b.txt", "analysis/a\\b.txt", "analysis/a%2fb.txt", "analysis/A.txt",
            "analysis/a--b.txt", "analysis/-a.txt", "analysis/a.b.txt", "analysis/a.TXT", "analysis/á.txt",
            "analysis/a", "analysis/.txt", "analysis/a.")) {
            malformed(rawManifest(List.of(path)), "invalid wire path " + path);
            expectIOException(() -> AnalysisManifest.encode(1, 1024, 2, Map.of(path, VALUE)),
                "invalid encode path " + path);
        }
    }

    private static void resourceValidation() throws Exception {
        byte[] encoded = AnalysisManifest.encode(1, 1024, 2, Map.of(FIRST_PATH, VALUE));
        expectIOException(() -> AnalysisManifest.validate(null, path -> null), "missing manifest");
        expectIOException(() -> AnalysisManifest.validate(new ByteArrayInputStream(encoded), path -> null),
            "missing resource");
        for (byte[] invalid : List.of(new byte[0], new byte[] {41}, new byte[] {42, 43, 44})) {
            TrackingStream input = new TrackingStream(invalid);
            expectIOException(() -> AnalysisManifest.validate(new ByteArrayInputStream(encoded), path -> input),
                "resource length or checksum mismatch " + Arrays.toString(invalid));
            check(input.closed, "invalid resource stream closed");
            check(invalid.length - input.available() <= VALUE.length + 1, "resource reads are bounded");
        }

        byte[] badChecksum = mutatedByte(encoded, encoded.length - 1, encoded[encoded.length - 1] ^ 1);
        expectIOException(() -> valid(badChecksum, Map.of(FIRST_PATH, VALUE)), "malformed checksum");
    }

    private static void runtimeFailure() {
        try {
            AnalysisManifest.verifyRuntime(AnalysisManifestTest.class, "/missing-analysis-fixture/");
        } catch (UnsatisfiedLinkError failure) {
            check(failure.getMessage().contains(AnalysisManifest.PATH), "runtime error names manifest");
            check(failure.getCause() instanceof IOException, "runtime error preserves resource failure");
            return;
        }
        throw new AssertionError("Expected runtime error for missing manifest");
    }

    private static void checkRecord(DataInputStream wire, String path, byte[] content) throws Exception {
        int length = wire.readUnsignedByte();
        check(length == path.length(), "wire path length");
        check(new String(wire.readNBytes(length), StandardCharsets.US_ASCII).equals(path), "wire path");
        check(wire.readInt() == content.length, "wire resource length");
        check(Arrays.equals(wire.readNBytes(32), sha256(content)), "wire SHA-256");
    }

    private static Map<String, byte[]> corpus(int entries) {
        Map<String, byte[]> corpus = new LinkedHashMap<>();
        for (int i = 0; i < entries; i++) {
            corpus.put("analysis/resource-" + i + ".txt", VALUE);
        }
        return corpus;
    }

    private static byte[] rawManifest(List<String> paths) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream wire = new DataOutputStream(buffer)) {
            wire.writeInt(0x4e4f414d);
            wire.writeByte(1);
            wire.writeByte(1);
            wire.writeInt(1024);
            wire.writeInt(AnalysisManifest.BALANCED_ENTRIES);
            wire.writeInt(paths.size());
            for (String path : paths) {
                byte[] bytes = path.getBytes(StandardCharsets.UTF_8);
                wire.writeByte(bytes.length);
                wire.write(bytes);
                wire.writeInt(VALUE.length);
                wire.write(sha256(VALUE));
            }
        }
        return buffer.toByteArray();
    }

    private static byte[] mutatedByte(byte[] original, int offset, int value) {
        byte[] copy = original.clone();
        copy[offset] = (byte) value;
        return copy;
    }

    private static byte[] mutatedInt(byte[] original, int offset, int value) {
        byte[] copy = original.clone();
        ByteBuffer.wrap(copy).putInt(offset, value);
        return copy;
    }

    private static void malformed(byte[] encoded, String description) throws Exception {
        TrackingStream manifest = new TrackingStream(encoded);
        expectIOException(() -> AnalysisManifest.validate(manifest, path -> {
            throw new AssertionError("Malformed manifest looked up resource " + path);
        }), description);
        check(manifest.closed, "malformed manifest stream closed");
    }

    private static void valid(byte[] encoded, Map<String, byte[]> corpus) throws IOException {
        Function<String, InputStream> resources = path -> new ByteArrayInputStream(corpus.get(path));
        AnalysisManifest.validate(new ByteArrayInputStream(encoded), resources);
    }

    private static byte[] sha256(byte[] input) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(input);
    }

    private static void expectIOException(CheckedAction action, String description) throws Exception {
        try {
            action.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Accepted " + description);
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }

    private static final class TrackingStream extends ByteArrayInputStream {
        private boolean closed;

        private TrackingStream(byte[] data) {
            super(data);
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
