package dev.nativeobfuscator;

import dev.nativeobfuscator.runtime.AnalysisManifest;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Standalone checks for synthetic corpus planning and encoding. */
public final class AnalysisCorpusTest {
    private static final long SEED = 0x16728c9aef013b21L;
    private static final Pattern PLOT = Pattern.compile("plot-([0-9]{4}) area=([0-9]{5}) shade=([0-9]{3})");
    private static final Pattern TOTAL = Pattern.compile("Total area=([0-9]{6}) plots=([0-9]{4})");
    private static final Set<String> ITEM_KINDS = Set.of("seed", "soil", "tool", "tray");

    public static void main(String[] args) throws Exception {
        var balanced = balanced(SEED);
        var maximum = AnalysisCorpus.plan(2, SEED, AnalysisManifest.MAXIMUM_BYTES, AnalysisManifest.MAXIMUM_ENTRIES);
        Map<String, byte[]> balancedFiles = checkGenerated(balanced);
        Map<String, byte[]> maximumFiles = checkGenerated(maximum);
        checkUtilityCatalog(balanced);
        require(maximum.byteCount() > balanced.byteCount() && maximum.entryCount() > balanced.entryCount(),
            "Maximum profile should expand the corpus");
        require(balancedFiles.containsKey("analysis/report-00.txt")
            && balancedFiles.containsKey("analysis/inventory-00.tsv"), "Balanced corpus should contain both formats");

        checkSameBytes(balancedFiles, AnalysisCorpus.generate(balanced(SEED)));
        var differentSeed = balanced(SEED ^ Long.MIN_VALUE);
        require(balanced.entries().equals(differentSeed.entries()), "Seed must leave the resource plan stable");
        Map<String, byte[]> differentFiles = checkGenerated(differentSeed);
        for (String name : balancedFiles.keySet()) {
            require(!Arrays.equals(balancedFiles.get(name), differentFiles.get(name)), "Seed had no effect on " + name);
        }

        for (AnalysisCorpus.EntryPlan entry : balanced.entries()) {
            List<String> original = lines(balancedFiles.get(entry.name()));
            List<String> expanded = lines(maximumFiles.get(entry.name()));
            int rowsEnd = entry.name().endsWith(".txt") ? original.size() - 1 : original.size();
            require(original.subList(0, rowsEnd).equals(expanded.subList(0, rowsEnd)),
                "Other profile documents changed the seeded stream for " + entry.name());
        }

        Locale originalLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"));
            checkSameBytes(balancedFiles, AnalysisCorpus.generate(balanced(SEED)));
        } finally {
            Locale.setDefault(originalLocale);
        }

        checkBudgets(balanced, balancedFiles);
        System.out.println("PASS analysis corpus");
    }

    private static void checkUtilityCatalog(AnalysisCorpus.Plan base) throws Exception {
        var utilities = AnalysisDecoys.generate(Map.of(), 2, SEED);
        var enriched = AnalysisCorpus.withUtilities(base, utilities);
        Map<String, byte[]> entries = AnalysisCorpus.generate(enriched);
        String index = enriched.componentIndex();
        require(index != null && entries.containsKey("analysis/component-index.tsv"), "Missing utility catalog");
        require(entries.size() == base.entryCount() + 1 && entries.size() == enriched.entryCount(),
            "Unexpected entry count after catalog planning");
        require(entries.values().stream().mapToInt(bytes -> bytes.length).sum() == enriched.byteCount(),
            "Catalog budget differs from generated resources");
        int row = 2;
        var lines = index.lines().toList();
        for (var method : utilities.nativeMethods().entrySet()) {
            String[] fields = lines.get(row++).split("\t");
            String classPath = method.getKey().substring(0, method.getKey().indexOf('#')) + ".class";
            byte[] owner = utilities.classes().get(classPath);
            String digest = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(owner));
            require(fields.length == 5 && fields[1].equals(method.getValue()) && fields[3].equals(digest)
                && entries.containsKey("analysis/" + fields[4]), "Invalid utility catalog row");
        }
        var capped = AnalysisCorpus.plan(1, SEED, 256, 2);
        require(AnalysisCorpus.withUtilities(capped, utilities) == capped,
            "A utility catalog exceeded the requested archive budget");
    }

    private static AnalysisCorpus.Plan balanced(long seed) {
        return AnalysisCorpus.plan(1, seed, AnalysisManifest.BALANCED_BYTES, AnalysisManifest.BALANCED_ENTRIES);
    }

    private static void checkBudgets(AnalysisCorpus.Plan balanced, Map<String, byte[]> balancedFiles) throws Exception {
        var minimum = AnalysisCorpus.plan(1, SEED, AnalysisCorpus.MIN_BYTE_BUDGET, AnalysisCorpus.MIN_ENTRY_BUDGET);
        checkGenerated(minimum);
        require(minimum.entryCount() == AnalysisCorpus.MIN_ENTRY_BUDGET, "Minimum budget should fit one document");
        var entryLimited = AnalysisCorpus.plan(1, SEED, AnalysisManifest.BALANCED_BYTES, 2);
        Map<String, byte[]> limitedFiles = checkGenerated(entryLimited);
        var firstEntry = balanced.entries().getFirst();
        require(entryLimited.entryCount() == 2 && entryLimited.entries().equals(List.of(firstEntry)),
            "Entry budget should keep the first document and manifest");
        require(Arrays.equals(limitedFiles.get(firstEntry.name()), balancedFiles.get(firstEntry.name())),
            "Entry budget changed the retained document");

        int exactBytes = entryLimited.byteCount();
        var exact = AnalysisCorpus.plan(1, SEED, exactBytes, AnalysisManifest.BALANCED_ENTRIES);
        require(exact.byteCount() == exactBytes && exact.entries().equals(entryLimited.entries()),
            "Exact byte budget should retain the complete document");
        var oneByteShort = AnalysisCorpus.plan(1, SEED, exactBytes - 1, AnalysisManifest.BALANCED_ENTRIES);
        Map<String, byte[]> shorterFiles = checkGenerated(oneByteShort);
        require(oneByteShort.entryCount() == 2 && oneByteShort.entries().getFirst().rows() == firstEntry.rows() - 1,
            "A one-byte reduction should trim a full row");
        List<String> shorter = lines(shorterFiles.get(firstEntry.name()));
        List<String> untrimmed = lines(balancedFiles.get(firstEntry.name()));
        require(shorter.subList(0, shorter.size() - 1).equals(untrimmed.subList(0, shorter.size() - 1)),
            "Byte budget changed the retained rows");

        AnalysisCorpus.Plan previous = minimum;
        for (int budget = AnalysisCorpus.MIN_BYTE_BUDGET; budget <= balanced.byteCount(); budget++) {
            var plan = AnalysisCorpus.plan(1, SEED, budget, AnalysisManifest.BALANCED_ENTRIES);
            require(plan.byteCount() >= previous.byteCount() && plan.byteCount() <= budget, "Byte budget accounting");
            require(plan.entryCount() >= previous.entryCount(), "Entry count regressed on a larger byte budget");
            for (int i = 0; i < previous.entries().size(); i++) {
                var previousEntry = previous.entries().get(i);
                var entry = plan.entries().get(i);
                require(entry.name().equals(previousEntry.name()) && entry.rows() >= previousEntry.rows(),
                    "Larger byte budget replaced previously planned rows");
            }
            previous = plan;
        }

        expectInvalid(0, AnalysisManifest.BALANCED_BYTES, AnalysisManifest.BALANCED_ENTRIES);
        expectInvalid(1, AnalysisCorpus.MIN_BYTE_BUDGET - 1, AnalysisManifest.BALANCED_ENTRIES);
        expectInvalid(1, AnalysisManifest.BALANCED_BYTES + 1, AnalysisManifest.BALANCED_ENTRIES);
        expectInvalid(2, AnalysisManifest.MAXIMUM_BYTES + 1, AnalysisManifest.MAXIMUM_ENTRIES);
        expectInvalid(1, AnalysisManifest.BALANCED_BYTES, AnalysisCorpus.MIN_ENTRY_BUDGET - 1);
        expectInvalid(1, AnalysisManifest.BALANCED_BYTES, AnalysisManifest.BALANCED_ENTRIES + 1);
        expectInvalid(2, AnalysisManifest.MAXIMUM_BYTES, AnalysisManifest.MAXIMUM_ENTRIES + 1);
    }

    private static Map<String, byte[]> checkGenerated(AnalysisCorpus.Plan plan) throws Exception {
        Map<String, byte[]> files = AnalysisCorpus.generate(plan);
        List<String> expectedOrder = new ArrayList<>();
        int actualBytes = 0;
        for (AnalysisCorpus.EntryPlan entry : plan.entries()) {
            String name = entry.name();
            expectedOrder.add(name);
            require(name.matches("analysis/(report-[0-9]{2}\\.txt|inventory-[0-9]{2}\\.tsv)"),
                "Unexpected corpus path " + name);
            byte[] data = files.get(name);
            require(data.length == entry.byteCount() && data.length <= AnalysisManifest.MAX_ENTRY_BYTES,
                "Unexpected corpus resource size " + name);
            actualBytes += data.length;
            require(data[data.length - 1] == '\n', "Corpus resource needs a final newline");
            for (byte b : data) {
                require(b == '\n' || b == '\t' || (b >= 32 && b <= 126), "Non-ASCII corpus resource " + name);
            }
            if (name.endsWith(".txt")) checkReport(lines(data), entry);
            else checkInventory(lines(data), entry);
        }
        expectedOrder.add(AnalysisManifest.PATH);
        require(expectedOrder.equals(new ArrayList<>(files.keySet())), "Corpus archive order");
        require(files.size() == plan.entryCount() && files.size() <= plan.entryBudget(), "Actual entry count");
        byte[] manifest = files.get(AnalysisManifest.PATH);
        require(manifest.length == plan.manifestBytes(), "Manifest byte count");
        actualBytes += manifest.length;
        require(actualBytes == plan.byteCount() && actualBytes <= plan.byteBudget(), "Actual byte count");
        byte[] digest = AnalysisManifest.validate(new ByteArrayInputStream(manifest),
            name -> new ByteArrayInputStream(files.get(name)));
        require(Arrays.equals(digest, MessageDigest.getInstance("SHA-256").digest(manifest)), "Manifest validation");
        return files;
    }

    private static void checkReport(List<String> lines, AnalysisCorpus.EntryPlan entry) {
        require(lines.size() == entry.rows() + 5, "Survey line count");
        require(lines.get(0).equals("Synthetic garden survey v1"), "Survey type");
        require(lines.get(1).matches("Survey [0-9]{2}")
            && Integer.parseInt(lines.get(1).substring(7)) == entry.document(), "Survey number");
        require(lines.get(2).equals("Area in square metres") && lines.get(3).equals("Shade in percent"), "Survey units");
        int area = 0;
        for (int i = 0; i < entry.rows(); i++) {
            var plot = PLOT.matcher(lines.get(4 + i));
            require(plot.matches(), "Invalid survey record");
            require(Integer.parseInt(plot.group(1)) == i, "Out-of-order survey plot");
            int plotArea = Integer.parseInt(plot.group(2));
            require(plotArea >= 20 && plotArea <= 499, "Invalid survey area");
            require(Integer.parseInt(plot.group(3)) <= 100, "Invalid survey shade");
            area += plotArea;
        }
        var total = TOTAL.matcher(lines.getLast());
        require(total.matches() && Integer.parseInt(total.group(1)) == area
            && Integer.parseInt(total.group(2)) == entry.rows(), "Invalid survey totals");
    }

    private static void checkInventory(List<String> lines, AnalysisCorpus.EntryPlan entry) {
        require(lines.size() == entry.rows() + 1, "Inventory line count");
        require(lines.getFirst().equals("synthetic_item\tkind\tstock\tminimum\tcost_cents"), "Inventory schema");
        for (int i = 0; i < entry.rows(); i++) {
            String[] item = lines.get(i + 1).split("\t", -1);
            require(item.length == 5 && item[0].matches("item-[0-9]{4}") && ITEM_KINDS.contains(item[1]),
                "Invalid inventory record");
            require(Integer.parseInt(item[0].substring(5)) == i, "Out-of-order inventory item");
            require(item[2].matches("[0-9]{4}") && Integer.parseInt(item[2]) <= 249, "Invalid inventory stock");
            require(item[3].matches("[0-9]{4}") && Integer.parseInt(item[3]) >= 2
                && Integer.parseInt(item[3]) <= 100, "Invalid inventory minimum");
            require(item[4].matches("[0-9]{5}") && Integer.parseInt(item[4]) >= 50
                && Integer.parseInt(item[4]) <= 6500, "Invalid inventory cost");
        }
    }

    private static List<String> lines(byte[] resource) {
        return new String(resource, StandardCharsets.US_ASCII).lines().toList();
    }

    private static void checkSameBytes(Map<String, byte[]> expected, Map<String, byte[]> actual) {
        require(expected.keySet().equals(actual.keySet()), "Corpus paths changed");
        for (String name : expected.keySet()) {
            require(Arrays.equals(expected.get(name), actual.get(name)), "Corpus output changed for " + name);
        }
    }

    private static void expectInvalid(int profile, int byteBudget, int entryBudget) {
        try {
            AnalysisCorpus.plan(profile, SEED, byteBudget, entryBudget);
            throw new AssertionError("Accepted invalid corpus budget or profile");
        } catch (IllegalArgumentException expected) {
            // Expected for a budget outside the profile bounds.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
