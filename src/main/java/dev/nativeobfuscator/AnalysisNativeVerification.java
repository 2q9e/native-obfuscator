package dev.nativeobfuscator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

/** Reconstructs the native helper plan from trusted original classfiles and checks staged library bytes. */
final class AnalysisNativeVerification {
    record TargetEvidence(String target, List<AnalysisNative.Evidence> helpers) {
        TargetEvidence {
            helpers = List.copyOf(helpers);
        }
    }

    private AnalysisNativeVerification() {}

    static List<TargetEvidence> verify(AnalysisConfig analysis, Map<String, byte[]> original,
                                       Set<String> expectedMethods, String runtimeId, Map<String, byte[]> staged,
                                       Map<String, String> librariesByTarget, List<String> errors)
            throws IOException, InterruptedException {
        return verify(analysis, original, expectedMethods, runtimeId, staged, librariesByTarget, errors, null);
    }

    static List<TargetEvidence> verify(AnalysisConfig analysis, Map<String, byte[]> original,
                                       Set<String> expectedMethods, String runtimeId, Map<String, byte[]> staged,
                                       Map<String, String> librariesByTarget, List<String> errors, String clang)
            throws IOException, InterruptedException {
        if (analysis.profile() != AnalysisConfig.Profile.MAXIMUM) return List.of();
        var nativeHelpers = new AnalysisNative(analysis.seed(), AnalysisNative.MAX_METHODS);
        Map<String, String> methodsByRoot = plan(nativeHelpers, original, expectedMethods, runtimeId, errors);
        if (methodsByRoot.isEmpty()) return List.of();
        if (librariesByTarget.isEmpty()) {
            errors.add("No staged libraries are available to verify planned native helpers");
            return List.of();
        }

        String objdump = AnalysisNative.objdump(clang);
        var results = new ArrayList<TargetEvidence>();
        for (String targetKey : librariesByTarget.keySet().stream().sorted().toList()) {
            NativeTarget target;
            try {
                target = NativeTarget.parse(targetKey);
            } catch (IllegalArgumentException invalid) {
                errors.add("Unknown target in staged native helper verification: " + targetKey);
                continue;
            }
            String entry = librariesByTarget.get(targetKey);
            String expectedEntry = "META-INF/native-obfuscator/" + runtimeId + "/" + target.key + "/" + target.file;
            var helpers = expectedEntry.equals(entry)
                ? verifyLibrary(nativeHelpers, methodsByRoot, target, entry, staged.get(entry), objdump)
                : unavailable(methodsByRoot, "staged library path does not match the expected runtime and target");
            requirePlannedEvidence(target, helpers, methodsByRoot, errors);
            results.add(new TargetEvidence(targetKey, helpers));
        }
        return List.copyOf(results);
    }

    private static Map<String, String> plan(AnalysisNative nativeHelpers, Map<String, byte[]> original,
                                            Set<String> expectedMethods, String runtimeId, List<String> errors) {
        var classPaths = new HashSet<String>();
        for (String signature : expectedMethods) {
            int separator = signature.indexOf('#');
            if (separator > 0) classPaths.add(signature.substring(0, separator) + ".class");
        }
        var methodsByRoot = new LinkedHashMap<String, String>();
        for (var entry : original.entrySet()) {
            String path = entry.getKey();
            if (!classPaths.contains(path) || path.startsWith("META-INF/") || path.equals("module-info.class")) continue;
            var type = new ClassNode();
            try {
                new ClassReader(entry.getValue()).accept(type, 0);
            } catch (RuntimeException invalid) {
                errors.add("Cannot plan native helpers for invalid original class " + path);
                continue;
            }
            if (!path.equals(type.name + ".class")) {
                errors.add("Cannot plan native helpers for original class with mismatched path " + path);
                continue;
            }
            for (var method : type.methods) {
                String signature = type.name + "#" + method.name + method.desc;
                if (!expectedMethods.contains(signature)) continue;
                String root = GeneratedNames.nativeFunction(runtimeId, signature);
                var site = nativeHelpers.prepare(method, root, signature);
                if (!site.enabled()) continue;
                nativeHelpers.accept(site);
                methodsByRoot.put(root, signature);
                if (nativeHelpers.selectedCount() == AnalysisNative.MAX_METHODS) return methodsByRoot;
            }
        }
        return methodsByRoot;
    }

    private static List<AnalysisNative.Evidence> verifyLibrary(AnalysisNative nativeHelpers, Map<String, String> planned,
                                                                NativeTarget target, String entry, byte[] binary,
                                                                String objdump) throws InterruptedException {
        if (binary == null) return unavailable(planned, "staged library bytes are missing");
        try {
            Archive.checkExpandedSize(entry, binary.length, 0);
            NativeImage.executableBytes(binary, target);
            Path image = Files.createTempFile("native-obfuscator-analysis-", ".bin");
            try {
                Files.write(image, binary);
                return nativeHelpers.verify(image, target, objdump);
            } finally {
                Files.deleteIfExists(image);
            }
        } catch (IOException failure) {
            return unavailable(planned, failure.getMessage());
        }
    }

    private static List<AnalysisNative.Evidence> unavailable(Map<String, String> planned, String reason) {
        return planned.keySet().stream().map(root ->
            new AnalysisNative.Evidence(root, "unavailable", reason, null, null, null)).toList();
    }

    private static void requirePlannedEvidence(NativeTarget target, List<AnalysisNative.Evidence> helpers,
                                               Map<String, String> methodsByRoot, List<String> errors) {
        var missing = new LinkedHashSet<>(methodsByRoot.keySet());
        for (var helper : helpers) {
            if (!missing.remove(helper.root())) {
                errors.add("Unexpected native helper evidence for " + target.key + ": " + helper.root());
            } else if (!helper.status().equals("verified")) {
                errors.add("Native helper " + helper.status() + " for " + target.key + " " + methodsByRoot.get(helper.root())
                    + ": " + helper.reason());
            }
        }
        for (String root : missing)
            errors.add("Missing native helper evidence for " + target.key + " " + methodsByRoot.get(root));
    }
}
