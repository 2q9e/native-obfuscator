package dev.nativeobfuscator;

import dev.nativeobfuscator.runtime.AnalysisManifest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class AnalysisVerification {
    record Report(String profile, String seed, int resourceBudget, int entryBudget,
                  int expectedResourceBytes, int expectedResourceEntries, boolean resourcesVerified,
                  String manifestSha256, List<String> anchoredNativeTargets) {
        Report {
            anchoredNativeTargets = List.copyOf(anchoredNativeTargets);
        }
    }

    record Result(Set<String> allowedEntries, Report report) {
        Result {
            allowedEntries = Set.copyOf(allowedEntries);
        }
    }

    private AnalysisVerification() {}

    static Archive.EntryCheck archiveLimits(AnalysisConfig config) {
        return new Archive.EntryCheck() {
            private long bytes;
            private int entries;

            @Override
            public void accept(String name, long expandedBytes) throws IOException {
                String prefix = "META-INF/native-obfuscator/";
                if (!name.startsWith(prefix)) return;
                int scopeEnd = name.indexOf('/', prefix.length());
                if (scopeEnd < 0 || !name.startsWith(AnalysisManifest.PREFIX, scopeEnd + 1)) return;
                if (expandedBytes == 0 || expandedBytes > AnalysisManifest.MAX_ENTRY_BYTES
                    || expandedBytes > config.resourceBudget() - bytes || entries == config.entryBudget())
                    throw new IOException("Analysis resources exceed the configured entry or byte limits: " + name);
                bytes += expandedBytes;
                entries++;
            }
        };
    }

    static Result verify(AnalysisConfig config, String loader, String root, Map<String, byte[]> staged,
                         Map<String, String> librariesByTarget, List<String> errors) throws IOException {
        var plan = AnalysisCorpus.plan(config.profile().id, config.seed(), config.resourceBudget(), config.entryBudget());
        var expected = AnalysisCorpus.generate(plan);
        var allowed = new HashSet<String>();
        int before = errors.size();
        for (var resource : expected.entrySet()) {
            String name = root + resource.getKey();
            allowed.add(name);
            byte[] data = staged.get(name);
            if (data == null) errors.add("Analysis resources are missing " + name);
            else if (!Arrays.equals(data, resource.getValue()))
                errors.add("Analysis resources differ from trusted build inputs: " + name);
        }
        for (String name : staged.keySet()) {
            if (name.startsWith(root + AnalysisManifest.PREFIX) && !allowed.contains(name))
                errors.add("Analysis resources include an unexpected entry: " + name);
        }

        byte[] digest = null;
        try {
            digest = AnalysisManifest.validate(stream(staged.get(root + AnalysisManifest.PATH)),
                path -> stream(staged.get(root + path)));
        } catch (IOException failure) {
            errors.add("Analysis resources failed manifest validation: " + failure.getMessage());
        }
        boolean resourcesVerified = errors.size() == before;

        var anchored = new ArrayList<String>();
        if (digest != null) {
            byte[] payload = AnalysisBinding.payload(digest);
            for (var library : librariesByTarget.entrySet()) {
                if (!library.getValue().startsWith(root)) continue;
                try {
                    AnalysisBinding.require(staged.get(library.getValue()),
                        NativeTarget.parse(library.getKey()), payload, loader);
                    anchored.add(library.getKey());
                } catch (IOException failure) {
                    errors.add("Native analysis binding invalid for " + library.getKey() + ": " + failure.getMessage());
                }
            }
        }

        return new Result(allowed, new Report(config.profile().option, Long.toUnsignedString(config.seed()),
            config.resourceBudget(), config.entryBudget(), plan.byteCount(), plan.entryCount(), resourcesVerified,
            digest == null ? null : HexFormat.of().formatHex(digest), anchored.stream().sorted().toList()));
    }

    private static ByteArrayInputStream stream(byte[] contents) {
        return contents == null ? null : new ByteArrayInputStream(contents);
    }
}
