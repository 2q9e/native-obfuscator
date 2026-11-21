package dev.nativeobfuscator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.HexFormat;

final class AnalysisBinding {
    static final String SECTION = ".noab";
    static final String REGISTRATION_SECTION = ".noar";
    private static final byte[] HEADER = {'N', 'O', 'A', 'B', 1};
    private static final byte[] REGISTRATION_HEADER = {'N', 'O', 'A', 'R', 1};
    private static final int REGISTRATION_PREFIX = REGISTRATION_HEADER.length + Integer.BYTES;
    private static final int DIGEST_BYTES = 32;
    private static final int MAX_REGISTRATION_CLASSES = 30_000;

    private AnalysisBinding() {}

    static byte[] payload(byte[] digest) {
        if (digest.length != 32) throw new IllegalArgumentException("Expected SHA-256 analysis digest");
        byte[] payload = new byte[HEADER.length + digest.length];
        System.arraycopy(HEADER, 0, payload, 0, HEADER.length);
        System.arraycopy(digest, 0, payload, HEADER.length, digest.length);
        return payload;
    }

    static void require(byte[] binary, NativeTarget target, byte[] binding, String loader) throws IOException {
        NativeImage.requireReadOnlySection(binary, target, SECTION, binding);
        NativeExports.requireFunction(binary, target, Protector.registrationExport(loader, true));
    }

    static byte[] registrationPayload(Collection<byte[]> metadataByIndex) throws IOException {
        int count = metadataByIndex.size();
        if (count == 0 || count > MAX_REGISTRATION_CLASSES)
            throw new IOException("Invalid native registration binding class count");
        var table = ByteBuffer.allocate(REGISTRATION_PREFIX + count * DIGEST_BYTES);
        table.put(REGISTRATION_HEADER).putInt(count);
        var digest = Archive.sha256();
        for (byte[] metadata : metadataByIndex) table.put(digest.digest(metadata));
        return table.array();
    }

    static void requireRegistrations(byte[] binary, NativeTarget target, byte[] registrationPayload) throws IOException {
        try {
            NativeImage.requireReadOnlySection(binary, target, REGISTRATION_SECTION, registrationPayload);
        } catch (IOException failure) {
            throw new IOException("Invalid native registration digest table: " + failure.getMessage(), failure);
        }
    }

    static String registrationSource(byte[] table) {
        var lines = new StringBuilder();
        var hex = HexFormat.ofDelimiter(", ").withPrefix("0x");
        for (int start = 0; start < table.length; start += DIGEST_BYTES) {
            lines.append("    ").append(hex.formatHex(table, start, Math.min(start + DIGEST_BYTES, table.length)))
                .append(",\n");
        }
        return """
            static const unsigned char registration_binding[] __attribute__((used, section("%s"))) = {
            %s};

            static int check_registration_digest(jint index, const unsigned char actual[32]) {
                if (index < 0 || (size_t)index >= (sizeof registration_binding - %d) / 32) return 0;
                const volatile unsigned char *expected = registration_binding + %d + (size_t)index * 32;
                unsigned int different = 0;
                for (size_t i = 0; i < 32; i++) different |= actual[i] ^ expected[i];
                return different == 0;
            }

            """.formatted(REGISTRATION_SECTION, lines, REGISTRATION_PREFIX, REGISTRATION_PREFIX);
    }

    static String source(byte[] binding) {
        String payload = HexFormat.ofDelimiter(", ").withPrefix("0x").formatHex(binding);
        return """
            static const unsigned char analysis_binding[] __attribute__((used, section("%s"))) = {
                %s
            };

            static int check_analysis_binding(JNIEnv *env, jbyteArray digest) {
                unsigned char actual[32];
                if (digest != NULL && (*env)->GetArrayLength(env, digest) == sizeof actual) {
                    (*env)->GetByteArrayRegion(env, digest, 0, sizeof actual, (jbyte *)actual);
                    if ((*env)->ExceptionCheck(env)) return 0;
                    const volatile unsigned char *expected = analysis_binding + %d;
                    unsigned int different = 0;
                    for (size_t i = 0; i < sizeof actual; i++) different |= actual[i] ^ expected[i];
                    if (different == 0) return 1;
                }
                jclass error = (*env)->FindClass(env, "java/lang/UnsatisfiedLinkError");
                if (error != NULL) (*env)->ThrowNew(env, error, "Native analysis binding digest mismatch");
                return 0;
            }

            """.formatted(SECTION, payload, HEADER.length);
    }
}
