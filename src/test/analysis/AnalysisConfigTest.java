package dev.nativeobfuscator;

public final class AnalysisConfigTest {
    public static void main(String[] args) {
        check(AnalysisConfig.resolve(null, null, null, null).equals(AnalysisConfig.OFF));
        check(AnalysisConfig.resolve("off", null, null, null).equals(AnalysisConfig.OFF));

        var balanced = AnalysisConfig.resolve("balanced", null, null, null);
        check(balanced.enabled() && balanced.seed() == 0);
        check(balanced.resourceBudget() == 256 * 1024 && balanced.entryBudget() == 32);

        var maximum = AnalysisConfig.resolve("maximum", "18446744073709551615", "1048576", "128");
        check(maximum.seed() == -1 && maximum.profile() == AnalysisConfig.Profile.MAXIMUM);
        check(maximum.resourceBudget() == 1024 * 1024 && maximum.entryBudget() == 128);
        check(AnalysisConfig.resolve("balanced", "001", "256", "2")
            .equals(new AnalysisConfig(AnalysisConfig.Profile.BALANCED, 1, 256, 2)));

        invalid("balanced or maximum", "off", "0", null, null);
        invalid("must be off", "Balanced", null, null, null);
        invalid("unsigned 64-bit", "balanced", "18446744073709551616", null, null);
        invalid("unsigned 64-bit", "balanced", "-1", null, null);
        invalid("unsigned 64-bit", "balanced", "+1", null, null);
        invalid("nonnegative decimal", "balanced", null, "-1", null);
        invalid("nonnegative decimal", "maximum", null, "2147483648", null);
        invalid("between 256 and 262144", "balanced", null, "262145", null);
        invalid("between 256 and 262144", "balanced", null, "255", null);
        invalid("between 2 and 32", "balanced", null, null, "33");
        invalid("between 2 and 128", "maximum", null, null, "1");
        System.out.println("Analysis profile configuration passed");
    }

    private static void invalid(String message, String profile, String seed, String bytes, String entries) {
        try {
            AnalysisConfig.resolve(profile, seed, bytes, entries);
            throw new AssertionError("Invalid configuration accepted");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains(message));
        }
    }

    private static void check(boolean result) {
        if (!result) throw new AssertionError("Unexpected analysis configuration");
    }
}
