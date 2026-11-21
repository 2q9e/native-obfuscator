package dev.nativeobfuscator;

import java.io.IOException;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

final class Archive {
    private static final int ENTRY_LIMIT = 128 * 1024 * 1024;
    private static final long TOTAL_LIMIT = 384L * 1024 * 1024;
    private static final int ENTRY_COUNT_LIMIT = 30_000;
    private static final int ZIP_NAME_LIMIT = 0xffff;
    // The writer emits each name twice. Allow for DEFLATE expansion, ZIP64 records and timestamps.
    static final long FILE_SIZE_LIMIT = TOTAL_LIMIT + TOTAL_LIMIT / 100
        + ENTRY_COUNT_LIMIT * (2L * ZIP_NAME_LIMIT + 256) + 1024;

    private Archive() {}

    static Map<String, byte[]> read(Path path) throws IOException {
        var files = new LinkedHashMap<String, byte[]>();
        long total = 0;
        try (var zip = new ZipFile(path.toFile())) {
            checkEntryCount(zip.size(), 0);
            var all = zip.entries();
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                String name = entry.getName();
                checkName(name);
                if (files.containsKey(name)) throw new IOException("Duplicate JAR entry " + name);
                if (name.toUpperCase(Locale.ROOT).matches("META-INF/([^/]+\\.(SF|RSA|DSA|EC)|SIG-[^/]+)"))
                    throw new IOException("Signed input JAR must be supplied before signing: " + name);
                checkExpandedSize(name, entry.getSize(), total);
                try (var input = zip.getInputStream(entry)) {
                    byte[] data = input.readNBytes((int) entry.getSize() + 1);
                    if (data.length != entry.getSize())
                        throw new IOException("Invalid expanded entry size: " + name);
                    var crc = new CRC32();
                    crc.update(data);
                    if (crc.getValue() != entry.getCrc()) throw new IOException("Entry CRC mismatch: " + name);
                    total += data.length;
                    files.put(name, data);
                }
            }
        }
        return files;
    }

    private static void checkName(String name) throws IOException {
        if (name == null || name.length() > ZIP_NAME_LIMIT) throw new IOException("Unsafe JAR entry name");
        String path = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        for (String component : path.split("/", -1)) {
            if (component.isEmpty() || component.equals(".") || component.equals("..")
                || component.indexOf('\\') >= 0 || component.indexOf(':') >= 0
                || component.chars().anyMatch(Character::isISOControl))
                throw new IOException("Unsafe JAR entry name");
        }
    }

    private static void checkEntryCount(int existing, int additional) throws IOException {
        if (additional > ENTRY_COUNT_LIMIT - existing) throw new IOException("JAR contains too many entries");
    }

    static void checkExpandedSize(String name, long size, long total) throws IOException {
        if (size < 0 || size > ENTRY_LIMIT || size > TOTAL_LIMIT - total)
            throw new IOException("Entry or expanded archive exceeds size limit: " + name);
    }

    private static void checkNameEncoding(String name) throws IOException {
        try {
            if (StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(name)).remaining() > ZIP_NAME_LIMIT)
                throw new IOException("JAR entry name exceeds ZIP size limit");
        } catch (CharacterCodingException failure) {
            throw new IOException("Invalid UTF-8 in JAR entry name", failure);
        }
    }

    static void preflight(Map<String, byte[]> files, long additionalBytes, int additionalEntries) throws IOException {
        if (additionalBytes < 0 || additionalEntries < 0) throw new IllegalArgumentException("Negative archive reservation");
        checkEntryCount(files.size(), additionalEntries);
        if (additionalBytes > TOTAL_LIMIT) throw new IOException("Expanded archive exceeds size limit");
        long total = additionalBytes;
        for (var file : files.entrySet()) {
            String name = file.getKey();
            checkName(name);
            checkNameEncoding(name);
            byte[] data = file.getValue();
            if (data == null) throw new IOException("Missing data for JAR entry: " + name);
            checkExpandedSize(name, data.length, total);
            total += data.length;
        }
    }

    static void checkFileSize(long size) throws IOException {
        if (size > FILE_SIZE_LIMIT) throw new IOException("Written JAR exceeds on-disk size limit");
    }

    record WrittenJar(long bytes, String sha256) {}

    static void write(Map<String, byte[]> files, Path output) throws IOException {
        write(files, output, false);
    }

    static WrittenJar write(Map<String, byte[]> files, Path output, boolean recordDigest) throws IOException {
        preflight(files, 0, 0);
        Path absolute = output.toAbsolutePath().normalize();
        Path temporary = Files.createTempFile(absolute.getParent(), ".native-obfuscator-", ".jar");
        try {
            MessageDigest digest = recordDigest ? sha256() : null;
            try (var fileStream = Files.newOutputStream(temporary);
                 var zip = new ZipOutputStream(digest == null ? fileStream : new DigestOutputStream(fileStream, digest))) {
                for (var file : files.entrySet()) {
                    ZipEntry entry = new ZipEntry(file.getKey());
                    entry.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0));
                    zip.putNextEntry(entry);
                    zip.write(file.getValue());
                    zip.closeEntry();
                }
            }
            long bytes = Files.size(temporary);
            checkFileSize(bytes);
            Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return new WrittenJar(bytes, digest == null ? null : HexFormat.of().formatHex(digest.digest()));
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
