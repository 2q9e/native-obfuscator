package dev.nativeobfuscator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class GeneratedNames {
    private static final int LENGTH = 300;
    private static final String PADDING_PACKAGE = "dev/nativeobfuscator/padding/";
    private static final int CLASS_LEAF_LENGTH = 200;
    private static final String PADDING_PUNCTUATION = "!$+_~-";

    private GeneratedNames() {}

    static String nativeFunction(String buildId, String symbol) {
        return identifier("method_", buildId, symbol, LENGTH);
    }

    static String paddingClass(String buildId, String symbol) {
        int packageLength = LENGTH - PADDING_PACKAGE.length() - CLASS_LEAF_LENGTH - 1;
        String namespace = identifier("P", buildId, symbol, packageLength);
        String leaf = withSeparators(identifier("C", buildId, symbol, CLASS_LEAF_LENGTH));
        return PADDING_PACKAGE + namespace + "/" + leaf;
    }

    static String paddingMethod(String buildId, String symbol) {
        return withSeparators(identifier("m", buildId, symbol, LENGTH));
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

    private static String withSeparators(String name) {
        var result = new StringBuilder(name);
        int start = 16 + Character.digit(name.charAt(1), 16);
        for (int i = start; i + 3 < name.length(); i += 32) {
            int selector = Character.digit(name.charAt(i), 16) % PADDING_PUNCTUATION.length();
            result.setCharAt(i, ' ');
            result.setCharAt(i + 1, ' ');
            result.setCharAt(i + 2, PADDING_PUNCTUATION.charAt(selector));
        }
        return result.toString();
    }
}
