package dev.nativeobfuscator;

import dev.nativeobfuscator.runtime.AnalysisManifest;
import java.util.Objects;

record AnalysisConfig(Profile profile, long seed, int resourceBudget, int entryBudget,
                      int nativeMethodLimit, int nativeHelpersPerRoot, int decoyMethods, int llvmSites) {
    private static final int MIN_RESOURCE_BUDGET = AnalysisCorpus.MIN_BYTE_BUDGET;
    private static final int MIN_ENTRY_BUDGET = AnalysisCorpus.MIN_ENTRY_BUDGET;
    static final AnalysisConfig OFF = new AnalysisConfig(Profile.OFF, 0, 0, 0, 0, 0, 0, 0);

    enum Profile {
        OFF(0, "off", 0, 0, 0, 0, 0, 0, 0),
        BALANCED(1, "balanced", AnalysisManifest.BALANCED_BYTES, AnalysisManifest.BALANCED_ENTRIES, 4, 1, 2, 4, 2),
        MAXIMUM(2, "maximum", AnalysisManifest.MAXIMUM_BYTES, AnalysisManifest.MAXIMUM_ENTRIES, 8, 3, 6, 16, 8);

        final int id;
        final String option;
        final int maxBytes;
        final int maxEntries;
        final int maxNativeMethods;
        final int maxHelpersPerRoot;
        final int defaultDecoys;
        final int maxDecoys;
        final int maxLlvmSites;

        Profile(int id, String option, int maxBytes, int maxEntries, int maxNativeMethods,
                int maxHelpersPerRoot, int defaultDecoys, int maxDecoys, int maxLlvmSites) {
            this.id = id;
            this.option = option;
            this.maxBytes = maxBytes;
            this.maxEntries = maxEntries;
            this.maxNativeMethods = maxNativeMethods;
            this.maxHelpersPerRoot = maxHelpersPerRoot;
            this.defaultDecoys = defaultDecoys;
            this.maxDecoys = maxDecoys;
            this.maxLlvmSites = maxLlvmSites;
        }

        static Profile parse(String option) {
            return switch (option) {
                case "off" -> OFF;
                case "balanced" -> BALANCED;
                case "maximum" -> MAXIMUM;
                default -> throw new IllegalArgumentException(
                    "--analysis-resistance must be off, balanced, or maximum");
            };
        }
    }

    AnalysisConfig {
        Objects.requireNonNull(profile, "analysis profile");
        if (profile == Profile.OFF) {
            if (seed != 0 || resourceBudget != 0 || entryBudget != 0 || nativeMethodLimit != 0
                || nativeHelpersPerRoot != 0 || decoyMethods != 0 || llvmSites != 0)
                throw new IllegalArgumentException("Analysis settings require an enabled profile");
        } else {
            checkBudget("--analysis-resource-budget", resourceBudget, MIN_RESOURCE_BUDGET, profile.maxBytes);
            checkBudget("--analysis-entry-budget", entryBudget, MIN_ENTRY_BUDGET, profile.maxEntries);
            checkBudget("--analysis-helper-methods", nativeMethodLimit, 0, profile.maxNativeMethods);
            checkBudget("--analysis-helpers-per-method", nativeHelpersPerRoot, 0, profile.maxHelpersPerRoot);
            checkBudget("--analysis-decoy-methods", decoyMethods, 0, profile.maxDecoys);
            checkBudget("--analysis-llvm-sites", llvmSites, 0, profile.maxLlvmSites);
            if ((nativeMethodLimit == 0) != (nativeHelpersPerRoot == 0))
                throw new IllegalArgumentException("Analysis helper limits must both be positive or both be zero");
        }
    }

    boolean enabled() {
        return profile != Profile.OFF;
    }

    static AnalysisConfig resolve(String profileOption, String seedOption, String resourceOption,
                                  String entryOption) {
        return resolve(profileOption, seedOption, resourceOption, entryOption, null, null, null, null);
    }

    static AnalysisConfig resolve(String profileOption, String seedOption, String resourceOption, String entryOption,
                                  String methodOption, String graphOption, String decoyOption, String llvmOption) {
        Profile profile = profileOption == null ? Profile.OFF : Profile.parse(profileOption);
        if (profile == Profile.OFF) {
            if (seedOption != null || resourceOption != null || entryOption != null || methodOption != null
                || graphOption != null || decoyOption != null || llvmOption != null)
                throw new IllegalArgumentException("Analysis seed and budgets require balanced or maximum");
            return OFF;
        }
        long seed = seedOption == null ? 0 : parseSeed(seedOption);
        int bytes = resourceOption == null ? profile.maxBytes : parseBudget("--analysis-resource-budget", resourceOption);
        int entries = entryOption == null ? profile.maxEntries : parseBudget("--analysis-entry-budget", entryOption);
        int graph = option("--analysis-helpers-per-method", graphOption, profile.maxHelpersPerRoot);
        int methods = option("--analysis-helper-methods", methodOption, graph == 0 ? 0 : profile.maxNativeMethods);
        if (methods == 0 && graphOption == null) graph = 0;
        int decoys = option("--analysis-decoy-methods", decoyOption, profile.defaultDecoys);
        int llvm = option("--analysis-llvm-sites", llvmOption, profile.maxLlvmSites);
        return new AnalysisConfig(profile, seed, bytes, entries, methods, graph, decoys, llvm);
    }

    private static long parseSeed(String value) {
        try {
            if (!value.matches("[0-9]+")) throw new NumberFormatException();
            return Long.parseUnsignedLong(value);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("--analysis-seed must be an unsigned 64-bit decimal integer");
        }
    }

    private static int option(String flag, String value, int fallback) {
        return value == null ? fallback : parseBudget(flag, value);
    }

    private static int parseBudget(String flag, String value) {
        try {
            if (!value.matches("[0-9]+")) throw new NumberFormatException();
            return Integer.parseInt(value);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(flag + " must be a nonnegative decimal integer");
        }
    }

    private static void checkBudget(String flag, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum)
            throw new IllegalArgumentException(flag + " must be between " + minimum + " and " + maximum);
    }
}
