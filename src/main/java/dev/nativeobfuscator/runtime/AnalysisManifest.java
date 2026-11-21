package dev.nativeobfuscator.runtime;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Canonical manifest shared by the host and the relocated runtime. */
public final class AnalysisManifest {
    public static final String PREFIX = "analysis/";
    public static final String PATH = PREFIX + "manifest.bin";
    public static final int BALANCED_BYTES = 262_144;
    public static final int MAXIMUM_BYTES = 1_048_576;
    public static final int BALANCED_ENTRIES = 32;
    public static final int MAXIMUM_ENTRIES = 128;
    public static final int MAX_ENTRY_BYTES = 32_768;

    private static final int MAGIC = 0x4e4f414d; // NOAM
    private static final int VERSION = 1;
    private static final int DIGEST_BYTES = 32;
    private static final int HEADER_BYTES = 18;
    private static final int RECORD_BYTES = 1 + Integer.BYTES + DIGEST_BYTES;
    private static final int MIN_PATH_BYTES = PREFIX.length() + 3;
    private static final int MAX_PATH_BYTES = 255;
    private static final String PATH_SEGMENT = "[a-z0-9]+(?:-[a-z0-9]+)*";
    private static final Pattern RESOURCE_PATH =
        Pattern.compile(PREFIX + "(?:" + PATH_SEGMENT + "/)*" + PATH_SEGMENT + "\\.[a-z0-9]+");

    private AnalysisManifest() {}

    public static int encodedSize(Collection<String> relativePaths) {
        if (relativePaths.size() >= MAXIMUM_ENTRIES)
            throw new IllegalArgumentException("Analysis manifest entry count exceeds its limit");
        int size = HEADER_BYTES;
        for (String path : relativePaths) {
            if (!isResourcePath(path)) throw new IllegalArgumentException("Invalid analysis resource path " + path);
            size += RECORD_BYTES + path.length();
            if (size > MAX_ENTRY_BYTES)
                throw new IllegalArgumentException("Analysis manifest exceeds per-entry size limit");
        }
        return size;
    }

    public static byte[] encode(int profile, int byteBudget, int entryBudget, Map<String, byte[]> corpus)
        throws IOException {
        checkHeader(profile, byteBudget, entryBudget, corpus.size());
        List<String> paths = new ArrayList<>(corpus.keySet());
        long total = HEADER_BYTES;
        int manifestSize = HEADER_BYTES;
        for (String path : paths) {
            if (!isResourcePath(path)) throw new IOException("Invalid analysis resource path " + path);
            byte[] bytes = corpus.get(path);
            if (bytes == null) throw new IOException("Missing analysis resource " + path);
            checkLength(path, bytes.length);
            manifestSize += RECORD_BYTES + path.length();
            checkManifestSize(manifestSize);
            total += RECORD_BYTES + path.length() + bytes.length;
            checkTotal(total, byteBudget);
        }
        paths.sort(null);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(manifestSize);
        MessageDigest digest = sha256();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeByte(VERSION);
            output.writeByte(profile);
            output.writeInt(byteBudget);
            output.writeInt(entryBudget);
            output.writeInt(paths.size());
            for (String path : paths) {
                byte[] content = corpus.get(path);
                output.writeByte(path.length());
                output.write(path.getBytes(StandardCharsets.US_ASCII));
                output.writeInt(content.length);
                output.write(digest.digest(content));
            }
        }
        return bytes.toByteArray();
    }

    /** Validates and closes the manifest and resource streams. Returns the manifest SHA-256. */
    public static byte[] validate(InputStream manifest, Function<String, InputStream> resources) throws IOException {
        if (manifest == null) throw new IOException("Missing analysis manifest");
        MessageDigest manifestDigest = sha256();
        String[] paths;
        int[] lengths;
        byte[][] checksums;
        try (DataInputStream input = new DataInputStream(new DigestInputStream(manifest, manifestDigest))) {
            if (input.readInt() != MAGIC) throw new IOException("Invalid analysis manifest magic");
            if (input.readUnsignedByte() != VERSION) throw new IOException("Unsupported analysis manifest version");
            int profile = input.readUnsignedByte();
            int byteBudget = input.readInt();
            int entryBudget = input.readInt();
            int entries = input.readInt();
            checkHeader(profile, byteBudget, entryBudget, entries);
            paths = new String[entries];
            lengths = new int[entries];
            checksums = new byte[entries][DIGEST_BYTES];
            long total = HEADER_BYTES;
            int manifestSize = HEADER_BYTES;
            String previous = "";
            for (int i = 0; i < entries; i++) {
                int pathLength = input.readUnsignedByte();
                if (pathLength < MIN_PATH_BYTES) throw new IOException("Invalid analysis resource path length");
                manifestSize += RECORD_BYTES + pathLength;
                checkManifestSize(manifestSize);
                total += RECORD_BYTES + pathLength;
                checkTotal(total, byteBudget);

                byte[] name = new byte[pathLength];
                input.readFully(name);
                String path = new String(name, StandardCharsets.US_ASCII);
                if (!isResourcePath(path)) throw new IOException("Invalid analysis resource path " + path);
                if (previous.compareTo(path) >= 0) throw new IOException("Analysis resources are out of order");
                previous = path;
                paths[i] = path;

                lengths[i] = input.readInt();
                checkLength(path, lengths[i]);
                total += lengths[i];
                checkTotal(total, byteBudget);
                input.readFully(checksums[i]);
            }
            if (input.read() != -1) throw new IOException("Trailing analysis manifest data");
        }

        MessageDigest resourceDigest = sha256();
        byte[] buffer = new byte[8192];
        for (int i = 0; i < paths.length; i++) {
            String path = paths[i];
            try (InputStream input = resources.apply(path)) {
                if (input == null) throw new IOException("Missing analysis resource " + path);
                int remaining = lengths[i];
                while (remaining > 0) {
                    int read = input.readNBytes(buffer, 0, Math.min(remaining, buffer.length));
                    if (read == 0) throw new IOException("Analysis resource length mismatch for " + path);
                    resourceDigest.update(buffer, 0, read);
                    remaining -= read;
                }
                if (input.read() != -1) throw new IOException("Analysis resource length mismatch for " + path);
                if (!MessageDigest.isEqual(checksums[i], resourceDigest.digest()))
                    throw new IOException("Analysis resource checksum mismatch for " + path);
            }
        }
        return manifestDigest.digest();
    }

    public static byte[] verifyRuntime(Class<?> runtime, String root) {
        try {
            return validate(runtime.getResourceAsStream(root + PATH),
                path -> runtime.getResourceAsStream(root + path));
        } catch (IOException failure) {
            UnsatisfiedLinkError error = new UnsatisfiedLinkError(
                "Cannot verify " + root + PATH + ": " + failure.getMessage());
            error.initCause(failure);
            throw error;
        }
    }

    private static boolean isResourcePath(String path) {
        return path != null && path.length() >= MIN_PATH_BYTES && path.length() <= MAX_PATH_BYTES
            && !path.equals(PATH) && RESOURCE_PATH.matcher(path).matches();
    }

    private static void checkHeader(int profile, int byteBudget, int entryBudget, int entries) throws IOException {
        if (profile != 1 && profile != 2) throw new IOException("Unsupported analysis manifest profile");
        int byteLimit = profile == 1 ? BALANCED_BYTES : MAXIMUM_BYTES;
        int entryLimit = profile == 1 ? BALANCED_ENTRIES : MAXIMUM_ENTRIES;
        if (byteBudget <= 0 || byteBudget > byteLimit || entryBudget < 2 || entryBudget > entryLimit)
            throw new IOException("Invalid analysis manifest budget");
        if (entries <= 0 || entries >= entryBudget) throw new IOException("Invalid analysis manifest entry count");
        checkTotal(HEADER_BYTES + (long) entries * (RECORD_BYTES + MIN_PATH_BYTES + 1), byteBudget);
    }

    private static void checkLength(String path, int length) throws IOException {
        if (length <= 0 || length > MAX_ENTRY_BYTES)
            throw new IOException("Invalid analysis resource length for " + path);
    }

    private static void checkManifestSize(int bytes) throws IOException {
        if (bytes > MAX_ENTRY_BYTES) throw new IOException("Analysis manifest exceeds per-entry size limit");
    }

    private static void checkTotal(long total, int byteBudget) throws IOException {
        if (total > byteBudget) throw new IOException("Analysis corpus exceeds byte budget");
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new AssertionError(failure);
        }
    }
}
