package dev.nativeobfuscator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class GeneratedNames {
    private static final int LENGTH = 128;

    private GeneratedNames() {}

    static String forSymbol(String prefix, String buildId, String symbol) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        byte[] hash = digest.digest((buildId + "/" + symbol).getBytes(StandardCharsets.UTF_8));
        return prefix + HexFormat.of().formatHex(hash).substring(0, LENGTH - prefix.length());
    }
}
