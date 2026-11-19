package dev.nativeobfuscator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
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

    private Archive() {}

    static Map<String, byte[]> read(Path path) throws IOException {
        var files = new LinkedHashMap<String, byte[]>();
        long total = 0;
        try (var zip = new ZipFile(path.toFile())) {
            if (zip.size() > 30000) throw new IOException("JAR contains too many entries");
            var all = zip.entries();
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                String name = entry.getName();
                checkName(name, entry.isDirectory());
                if (files.containsKey(name)) throw new IOException("Duplicate JAR entry " + name);
                if (name.toUpperCase(Locale.ROOT).matches("META-INF/([^/]+\\.(SF|RSA|DSA|EC)|SIG-[^/]+)"))
                    throw new IOException("Signed input JAR must be supplied before signing: " + name);
                if (entry.getSize() < 0 || entry.getSize() > ENTRY_LIMIT || entry.getSize() + total > TOTAL_LIMIT)
                    throw new IOException("Entry or expanded archive exceeds size limit: " + name);
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

    private static void checkName(String name, boolean directory) throws IOException {
        String path = directory ? name.substring(0, name.length() - 1) : name;
        for (String component : path.split("/", -1)) {
            if (component.isEmpty() || component.equals(".") || component.equals("..")
                || component.indexOf('\\') >= 0 || component.indexOf(':') >= 0
                || component.chars().anyMatch(Character::isISOControl))
                throw new IOException("Unsafe JAR entry name");
        }
    }

    static void write(Map<String, byte[]> files, Path output) throws IOException {
        Path absolute = output.toAbsolutePath().normalize();
        Path temporary = Files.createTempFile(absolute.getParent(), ".native-obfuscator-", ".jar");
        try {
            try (var zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
                for (var file : files.entrySet()) {
                    ZipEntry entry = new ZipEntry(file.getKey());
                    entry.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0));
                    zip.putNextEntry(entry);
                    zip.write(file.getValue());
                    zip.closeEntry();
                }
            }
            Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
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
