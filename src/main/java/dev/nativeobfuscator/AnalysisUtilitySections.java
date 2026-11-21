package dev.nativeobfuscator;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

final class AnalysisUtilitySections {
    private static final long MAX_METHOD_BYTES = 16 * 1024;

    private AnalysisUtilitySections() {}

    static Map<String, String> names(FilePadding.Generation utilities) {
        var names = new LinkedHashMap<String, String>();
        for (String method : utilities.nativeMethods().keySet().stream().sorted().toList()) {
            int tag = 0x1000 | ((method.hashCode() & 0xff) << 4) | names.size();
            names.put(method, ".nou" + Integer.toHexString(tag).substring(1));
        }
        return Collections.unmodifiableMap(names);
    }

    static Map<String, Long> require(byte[] binary, NativeTarget target, Map<String, String> sections)
            throws IOException {
        var sizes = new LinkedHashMap<String, Long>();
        for (var section : sections.entrySet()) {
            long bytes;
            try {
                bytes = NativeImage.requireCodeSection(binary, target, section.getValue());
            } catch (IOException failure) {
                throw new IOException("Native utility section " + section.getValue() + " for "
                    + section.getKey() + " is invalid on " + target.key + ": " + failure.getMessage(), failure);
            }
            if (bytes > MAX_METHOD_BYTES)
                throw new IOException("Native utility code exceeds its section budget on " + target.key);
            sizes.put(section.getKey(), bytes);
        }
        return Collections.unmodifiableMap(sizes);
    }
}
