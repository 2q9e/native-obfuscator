package dev.nativeobfuscator;

public final class AnalysisConfigTest {
    public static void main(String[] args) {
        check(AnalysisConfig.resolve(null, null, null, null).equals(AnalysisConfig.OFF));
        check(AnalysisConfig.resolve("off", null, null, null).equals(AnalysisConfig.OFF));

        var balanced = AnalysisConfig.resolve("balanced", null, null, null);
        check(balanced.enabled() && balanced.seed() == 0);
        check(balanced.resourceBudget() == 256 * 1024 && balanced.entryBudget() == 32);
        check(balanced.nativeMethodLimit() == 4 && balanced.nativeHelpersPerRoot() == 1);
        check(balanced.decoyMethods() == 2 && balanced.llvmSites() == 2);

        var maximum = AnalysisConfig.resolve("maximum", "18446744073709551615", "1048576", "128");
        check(maximum.seed() == -1 && maximum.profile() == AnalysisConfig.Profile.MAXIMUM);
        check(maximum.resourceBudget() == 1024 * 1024 && maximum.entryBudget() == 128);
        check(maximum.nativeMethodLimit() == 8 && maximum.nativeHelpersPerRoot() == 3);
        check(maximum.decoyMethods() == 6 && maximum.llvmSites() == 8);
        check(AnalysisConfig.resolve("balanced", "001", "256", "2")
            .equals(new AnalysisConfig(AnalysisConfig.Profile.BALANCED, 1, 256, 2, 4, 1, 2, 2)));
        var disabled = AnalysisConfig.resolve("maximum", null, null, null, null, "00", "0", "0");
        check(disabled.nativeMethodLimit() == 0 && disabled.nativeHelpersPerRoot() == 0
            && disabled.decoyMethods() == 0 && disabled.llvmSites() == 0);
        rejectsAdvanced("balanced", "5", null, null, null);
        rejectsAdvanced("balanced", null, "2", null, null);
        rejectsAdvanced("maximum", null, null, "17", null);
        rejectsAdvanced("maximum", null, null, null, "9");
        rejectsAdvanced("maximum", "0", "2", null, null);
        rejectsAdvanced("off", null, null, "0", null);

        String route = "native-route<seed=42;max=1;prefix=method_>,";
        String compare = "native-compare<seed=42;max=1;prefix=method_>,";
        String mix = "native-mix<seed=42;max=1;prefix=method_>,";
        check(extendedPipeline("maximum", "0").equals("legacy"));
        check(extendedPipeline("maximum", "1").equals(route + "legacy"));
        check(extendedPipeline("maximum", "2").equals(route + mix + "legacy"));
        check(extendedPipeline("maximum", "3").equals(route + compare + mix + "legacy"));
        check(extendedPipeline("maximum", "8").equals(
            route + compare + "native-mix<seed=42;max=6;prefix=method_>,legacy"));
        check(extendedPipeline("balanced", "2").equals("native-mix<seed=42;max=2;prefix=method_>,legacy"));

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

    private static String extendedPipeline(String profile, String budget) {
        var analysis = AnalysisConfig.resolve(profile, "42", null, null, null, null, null, budget);
        return Main.extendedAnalysisPasses("legacy", analysis);
    }

    private static void invalid(String message, String profile, String seed, String bytes, String entries) {
        try {
            AnalysisConfig.resolve(profile, seed, bytes, entries);
            throw new AssertionError("Invalid configuration accepted");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains(message));
        }
    }

    private static void rejectsAdvanced(String profile, String methods, String graph, String decoys, String llvm) {
        try {
            AnalysisConfig.resolve(profile, null, null, null, methods, graph, decoys, llvm);
            throw new AssertionError("Invalid advanced configuration accepted");
        } catch (IllegalArgumentException expected) {
            check(!expected.getMessage().isBlank());
        }
    }

    private static void check(boolean result) {
        if (!result) throw new AssertionError("Unexpected analysis configuration");
    }
}
