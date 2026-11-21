package dev.nativeobfuscator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class GeneratedNames {
    private static final int LENGTH = 300;

    private GeneratedNames() {}

    static String nativeFunction(String buildId, String symbol) {
        return identifier("method_", buildId, symbol, LENGTH);
    }

    private static String identifier(String prefix, String buildId, String symbol, int length) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        byte[] input = (prefix + "/" + buildId + "/" + symbol).getBytes(StandardCharsets.UTF_8);
        var name = new StringBuilder(prefix);
        for (int block = 0; name.length() < length; block++) {
            digest.update(input);
            digest.update((byte) block);
            name.append(HexFormat.of().formatHex(digest.digest()));
        }
        return name.substring(0, length);
    }

}
