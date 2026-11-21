package dev.nativeobfuscator;

import java.io.IOException;
import java.util.HexFormat;

final class AnalysisBinding {
    static final String SECTION = ".noab";
    private static final byte[] HEADER = {'N', 'O', 'A', 'B', 1};

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
