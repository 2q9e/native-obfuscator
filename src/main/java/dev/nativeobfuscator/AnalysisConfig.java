package dev.nativeobfuscator;

record AnalysisConfig(Profile profile, long seed, int resourceBudget, int entryBudget) {
    static final int MIN_RESOURCE_BUDGET = 256;
    static final int MIN_ENTRY_BUDGET = 2;
    static final AnalysisConfig OFF = new AnalysisConfig(Profile.OFF, 0, 0, 0);

    enum Profile {
        OFF(0, "off", 0, 0),
        BALANCED(1, "balanced", 256 * 1024, 32),
        MAXIMUM(2, "maximum", 1024 * 1024, 128);

        final int id;
        final String option;
        final int maxBytes;
        final int maxEntries;

        Profile(int id, String option, int maxBytes, int maxEntries) {
            this.id = id;
            this.option = option;
            this.maxBytes = maxBytes;
            this.maxEntries = maxEntries;
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
        if (profile == Profile.OFF) {
            if (seed != 0 || resourceBudget != 0 || entryBudget != 0)
                throw new IllegalArgumentException("Analysis settings require an enabled profile");
        } else {
            checkBudget("--analysis-resource-budget", resourceBudget, MIN_RESOURCE_BUDGET, profile.maxBytes);
            checkBudget("--analysis-entry-budget", entryBudget, MIN_ENTRY_BUDGET, profile.maxEntries);
        }
    }

    boolean enabled() {
        return profile != Profile.OFF;
    }

    static AnalysisConfig resolve(String profileOption, String seedOption, String resourceOption,
                                  String entryOption) {
        Profile profile = profileOption == null ? Profile.OFF : Profile.parse(profileOption);
        if (profile == Profile.OFF) {
            if (seedOption != null || resourceOption != null || entryOption != null)
                throw new IllegalArgumentException("Analysis seed and budgets require balanced or maximum");
            return OFF;
        }
        long seed = seedOption == null ? 0 : parseSeed(seedOption);
        int bytes = resourceOption == null ? profile.maxBytes : parseBudget("--analysis-resource-budget", resourceOption);
        int entries = entryOption == null ? profile.maxEntries : parseBudget("--analysis-entry-budget", entryOption);
        return new AnalysisConfig(profile, seed, bytes, entries);
    }

    private static long parseSeed(String value) {
        try {
            if (!value.matches("[0-9]+")) throw new NumberFormatException();
            return Long.parseUnsignedLong(value);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("--analysis-seed must be an unsigned 64-bit decimal integer");
        }
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
