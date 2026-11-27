package dev.nativeobfuscator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

record LlvmScope(List<Pattern> includes, List<Pattern> excludes) {
    static final LlvmScope ALL = new LlvmScope(List.of(), List.of());
    private static final String SELECTION_FILE = "llvm-functions.txt";

    LlvmScope {
        includes = List.copyOf(includes);
        excludes = List.copyOf(excludes);
    }

    boolean enabled() {
        return !includes.isEmpty() || !excludes.isEmpty();
    }

    String preset(String pipeline) {
        return enabled() ? pipeline.replace(";prefix=method_>", ";prefix=method_;functions-file=" + SELECTION_FILE + ">")
            : pipeline;
    }

    void writeSelection(Path workspace, Map<String, String> symbols) throws IOException {
        if (!enabled()) return;
        var selection = new StringBuilder();
        symbols.forEach((symbol, method) -> {
            if ((includes.isEmpty() || matches(includes, method)) && !matches(excludes, method))
                selection.append(symbol).append('\n');
        });
        Files.writeString(workspace.resolve(SELECTION_FILE), selection, StandardCharsets.US_ASCII);
    }

    private static boolean matches(List<Pattern> patterns, String method) {
        return patterns.stream().anyMatch(pattern -> pattern.matcher(method).matches());
    }
}
