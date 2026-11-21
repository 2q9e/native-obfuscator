package dev.nativeobfuscator;

import dev.nativeobfuscator.runtime.AnalysisManifest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Builds bounded, synthetic resources whose contents are independent of the input archive. */
final class AnalysisCorpus {
    static final int MIN_BYTE_BUDGET = 256;
    static final int MIN_ENTRY_BUDGET = 2;

    private static final int[] BALANCED_ROWS = {8, 12, 16, 20};
    private static final int[] MAXIMUM_ROWS = {24, 32, 40, 48, 56, 64, 72, 80, 88, 96, 104, 112};
    private static final String REPORT_HEADER = "Synthetic garden survey v1\nSurvey ";
    private static final String REPORT_UNITS = "\nArea in square metres\nShade in percent\n";
    private static final String INVENTORY_HEADER = "synthetic_item\tkind\tstock\tminimum\tcost_cents\n";
    private static final List<String> INVENTORY_KINDS = List.of("seed", "soil", "tool", "tray");

    private AnalysisCorpus() {}

    static Plan plan(int profile, long seed, int byteBudget, int entryBudget) {
        int maxBytes;
        int maxEntries;
        int[] preferredRows;
        switch (profile) {
            case 1 -> {
                maxBytes = AnalysisManifest.BALANCED_BYTES;
                maxEntries = AnalysisManifest.BALANCED_ENTRIES;
                preferredRows = BALANCED_ROWS;
            }
            case 2 -> {
                maxBytes = AnalysisManifest.MAXIMUM_BYTES;
                maxEntries = AnalysisManifest.MAXIMUM_ENTRIES;
                preferredRows = MAXIMUM_ROWS;
            }
            default -> throw new IllegalArgumentException("Invalid analysis corpus profile " + profile);
        }
        if (byteBudget < MIN_BYTE_BUDGET || byteBudget > maxBytes) {
            throw new IllegalArgumentException("Analysis byte budget must be between " + MIN_BYTE_BUDGET
                + " and " + maxBytes);
        }
        if (entryBudget < MIN_ENTRY_BUDGET || entryBudget > maxEntries) {
            throw new IllegalArgumentException("Analysis entry budget must be between " + MIN_ENTRY_BUDGET
                + " and " + maxEntries);
        }

        var entries = new ArrayList<EntryPlan>();
        var names = new ArrayList<String>();
        int corpusBytes = 0;
        int manifestBytes = AnalysisManifest.encodedSize(names);
        // Keep an ordered prefix of the profile's documents, shortening the last one when needed.
        for (int i = 0; i < preferredRows.length && i + 1 < entryBudget; i++) {
            Format format = i % 2 == 0 ? Format.REPORT : Format.INVENTORY;
            int document = i / 2;
            String name = format.name(document);
            names.add(name);
            int expandedManifestBytes = AnalysisManifest.encodedSize(names);
            int available = Math.min(AnalysisManifest.MAX_ENTRY_BYTES, byteBudget - corpusBytes - expandedManifestBytes);
            if (available < format.byteCount(1)) break;
            int rows = Math.min(preferredRows[i], (available - format.fixedBytes) / format.rowBytes);
            int bytes = format.byteCount(rows);
            entries.add(new EntryPlan(name, format, document, rows, bytes));
            corpusBytes += bytes;
            manifestBytes = expandedManifestBytes;
        }
        if (entries.isEmpty()) throw new IllegalStateException("Analysis corpus cannot fit its minimum budget");
        return new Plan(profile, seed, byteBudget, entryBudget, corpusBytes + manifestBytes, manifestBytes, entries);
    }

    static Map<String, byte[]> generate(Plan plan) throws IOException {
        var additions = new LinkedHashMap<String, byte[]>();
        for (EntryPlan entry : plan.entries()) {
            var text = new StringBuilder(entry.byteCount());
            Random random = new Random(documentSeed(plan.seed(), entry));
            switch (entry.format()) {
                case REPORT -> writeReport(text, entry, random);
                case INVENTORY -> writeInventory(text, entry, random);
            }
            byte[] bytes = text.toString().getBytes(StandardCharsets.US_ASCII);
            if (bytes.length != entry.byteCount()) throw new IllegalStateException("Unexpected analysis resource size");
            additions.put(entry.name(), bytes);
        }
        byte[] manifest = AnalysisManifest.encode(plan.profile(), plan.byteBudget(), plan.entryBudget(), additions);
        if (manifest.length != plan.manifestBytes()) throw new IllegalStateException("Unexpected analysis manifest size");
        additions.put(AnalysisManifest.PATH, manifest);
        return Collections.unmodifiableMap(additions);
    }

    private static void writeReport(StringBuilder text, EntryPlan entry, Random random) {
        appendDecimal(text.append(REPORT_HEADER), entry.document(), 2).append(REPORT_UNITS);
        int totalArea = 0;
        for (int plot = 0; plot < entry.rows(); plot++) {
            int area = 20 + random.nextInt(480);
            int shade = random.nextInt(101);
            totalArea += area;
            appendDecimal(text.append("plot-"), plot, 4);
            appendDecimal(text.append(" area="), area, 5);
            appendDecimal(text.append(" shade="), shade, 3).append('\n');
        }
        appendDecimal(text.append("Total area="), totalArea, 6);
        appendDecimal(text.append(" plots="), entry.rows(), 4).append('\n');
    }

    private static void writeInventory(StringBuilder text, EntryPlan entry, Random random) {
        text.append(INVENTORY_HEADER);
        for (int item = 0; item < entry.rows(); item++) {
            appendDecimal(text.append("item-"), item, 4).append('\t');
            text.append(INVENTORY_KINDS.get(random.nextInt(INVENTORY_KINDS.size())));
            appendDecimal(text.append('\t'), random.nextInt(250), 4);
            appendDecimal(text.append('\t'), 2 + random.nextInt(99), 4);
            appendDecimal(text.append('\t'), 50 + random.nextInt(6451), 5).append('\n');
        }
    }

    private static StringBuilder appendDecimal(StringBuilder text, int value, int width) {
        String digits = Integer.toString(value);
        return text.append("000000", 0, width - digits.length()).append(digits);
    }

    private static long documentSeed(long seed, EntryPlan entry) {
        // Mix all seed bits, then use a reproducible Random stream for each document.
        long mixed = seed ^ (entry.format().seedSalt + 0x9e3779b97f4a7c15L * entry.document());
        mixed = (mixed ^ (mixed >>> 30)) * 0xbf58476d1ce4e5b9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94d049bb133111ebL;
        return mixed ^ (mixed >>> 31);
    }

    record Plan(int profile, long seed, int byteBudget, int entryBudget, int byteCount, int manifestBytes,
                List<EntryPlan> entries) {
        Plan {
            entries = List.copyOf(entries);
        }

        int entryCount() {
            return entries.size() + 1;
        }
    }

    record EntryPlan(String name, Format format, int document, int rows, int byteCount) {}

    private enum Format {
        REPORT("report-", ".txt", 0xd12528cde9a43123L,
            REPORT_HEADER.length() + 2 + REPORT_UNITS.length() + "Total area=000000 plots=0000\n".length(),
            "plot-0000 area=00000 shade=000\n".length()),
        INVENTORY("inventory-", ".tsv", 0x5f80ae189756bc24L,
            INVENTORY_HEADER.length(), "item-0000\tseed\t0000\t0000\t00000\n".length());

        private final String stem;
        private final String extension;
        private final long seedSalt;
        private final int fixedBytes;
        private final int rowBytes;

        Format(String stem, String extension, long seedSalt, int fixedBytes, int rowBytes) {
            this.stem = stem;
            this.extension = extension;
            this.seedSalt = seedSalt;
            this.fixedBytes = fixedBytes;
            this.rowBytes = rowBytes;
        }

        private String name(int document) {
            return appendDecimal(new StringBuilder(AnalysisManifest.PREFIX).append(stem), document, 2)
                .append(extension).toString();
        }

        private int byteCount(int rows) {
            return fixedBytes + rows * rowBytes;
        }
    }
}
