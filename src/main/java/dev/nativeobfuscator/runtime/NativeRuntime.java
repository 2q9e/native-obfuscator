package dev.nativeobfuscator.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** Copied into the protected JAR under an input-specific name. */
public final class NativeRuntime {
    public static final int MAX_REGISTRATION_BYTES = 16 * 1024 * 1024;

    static {
        load();
    }

    private NativeRuntime() {}

    public static void register(Class<?> declaringClass, int index) {
        String path = new StringBuilder(resourceRoot()).append("registration/").append(index).append(".bin").toString();
        try (InputStream input = resource(path)) {
            byte[] metadata = input.readNBytes(MAX_REGISTRATION_BYTES + 1);
            if (metadata.length == 0 || metadata.length > MAX_REGISTRATION_BYTES)
                throw new IOException("Invalid native registration metadata size");
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(metadata);
            register0(declaringClass, index, metadata, digest);
        } catch (IOException | NoSuchAlgorithmException failure) {
            var error = new UnsatisfiedLinkError("Cannot load " + path + ": " + failure.getMessage());
            error.initCause(failure);
            throw error;
        }
    }

    private static native void register0(Class<?> declaringClass, int index, byte[] metadata, byte[] digest);

    private static void load() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String platform = os.startsWith("windows") ? "windows" : os.equals("linux") ? "linux" : "";
        String machine = switch (arch) {
            case "amd64", "x86_64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> "";
        };
        if (platform.isEmpty() || machine.isEmpty())
            throw new UnsatisfiedLinkError("Unsupported native target " + os + "/" + arch);

        String binary = platform.equals("windows") ? "protected.dll" : "libprotected.so";
        // This code runs on the first native call. Avoid linking dynamic
        // string concatenation call sites just to look up the two resources.
        String libraryPath = new StringBuilder(resourceRoot()).append(machine).append('-')
            .append(platform).append('/').append(binary).toString();
        try {
            byte[] expected;
            try (InputStream sha = resource(libraryPath.concat(".sha256"))) {
                byte[] hex = sha.readNBytes(65);
                if (hex.length != 64) throw new IOException("Invalid native library checksum length");
                expected = HexFormat.of().parseHex(new String(hex, java.nio.charset.StandardCharsets.US_ASCII));
            }
            Path directory;
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                directory = Files.createTempDirectory("native-obfuscator-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            } else {
                directory = Files.createTempDirectory("native-obfuscator-");
            }
            directory.toFile().deleteOnExit();
            Path file = directory.resolve(binary);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try {
                try (InputStream input = resource(libraryPath);
                     var output = Files.newOutputStream(file, java.nio.file.StandardOpenOption.CREATE_NEW,
                         java.nio.file.StandardOpenOption.WRITE)) {
                    byte[] buffer = new byte[16384];
                    long total = 0;
                    for (int read; (read = input.read(buffer)) != -1;) {
                        total += read;
                        if (total > 128L * 1024 * 1024) throw new IOException("Native resource exceeds size limit");
                        digest.update(buffer, 0, read);
                        output.write(buffer, 0, read);
                    }
                }
                if (!MessageDigest.isEqual(expected, digest.digest()))
                    throw new IOException("Native library checksum mismatch");
                file.toFile().deleteOnExit();
                System.load(file.toAbsolutePath().toString());
            } catch (IOException | LinkageError | RuntimeException failure) {
                try {
                    Files.deleteIfExists(file);
                    Files.deleteIfExists(directory);
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        } catch (IOException | NoSuchAlgorithmException | IllegalArgumentException failure) {
            var error = new UnsatisfiedLinkError("Cannot load " + libraryPath + ": " + failure.getMessage());
            error.initCause(failure);
            throw error;
        }
    }

    private static String resourceRoot() {
        String name = NativeRuntime.class.getName();
        return new StringBuilder("/META-INF/native-obfuscator/")
            .append(name, name.lastIndexOf('.') + 1, name.length()).append('/').toString();
    }

    private static InputStream resource(String path) throws IOException {
        InputStream input = NativeRuntime.class.getResourceAsStream(path);
        if (input == null) throw new IOException("Missing native resource " + path);
        return input;
    }
}
