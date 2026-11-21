package dev.nativeobfuscator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ArchiveLimitsTest {
    private static final long ENTRY_LIMIT = 128L * 1024 * 1024;
    private static final long TOTAL_LIMIT = 384L * 1024 * 1024;
    private static final int ENTRY_COUNT_LIMIT = 30_000;
    private static final byte[] EMPTY = new byte[0];

    public static void main(String[] args) throws Exception {
        checkSizeBoundaries();
        Path work = Files.createTempDirectory("archive-limits-");
        try {
            checkRoundTripAndPreservation(work.resolve("output.jar"));
            checkEntryCount(work.resolve("at-limit.jar"), work.resolve("above-limit.jar"));
        } finally {
            try (var children = Files.list(work)) {
                for (Path child : children.toList()) Files.delete(child);
            }
            Files.delete(work);
        }
        System.out.println("Archive limit checks passed");
    }

    private static void checkSizeBoundaries() throws Exception {
        Archive.checkExpandedSize("entry", ENTRY_LIMIT, TOTAL_LIMIT - ENTRY_LIMIT);
        reject("exceeds size limit", () -> Archive.checkExpandedSize("entry", ENTRY_LIMIT + 1, 0));
        reject("exceeds size limit", () -> Archive.checkExpandedSize("entry", ENTRY_LIMIT, TOTAL_LIMIT - ENTRY_LIMIT + 1));

        Map<String, byte[]> files = Map.of("entry", new byte[7]);
        Archive.preflight(files, TOTAL_LIMIT - 7, ENTRY_COUNT_LIMIT - 1);
        reject("exceeds size limit", () -> Archive.preflight(files, TOTAL_LIMIT - 6, 3));
        reject("exceeds size limit", () -> Archive.preflight(files, Long.MAX_VALUE, 3));
        reject("too many entries", () -> Archive.preflight(files, 0, ENTRY_COUNT_LIMIT));

        Archive.checkFileSize(TOTAL_LIMIT + ENTRY_COUNT_LIMIT * (2L * 65_535 + 150));
        Archive.checkFileSize(Archive.FILE_SIZE_LIMIT);
        reject("exceeds on-disk size limit", () -> Archive.checkFileSize(Archive.FILE_SIZE_LIMIT + 1));
    }

    private static void checkRoundTripAndPreservation(Path output) throws Exception {
        String longName = "\u00e9".repeat(32_700) + "x";
        Map<String, byte[]> entries = Map.of("folder/", EMPTY, "folder/file", new byte[] {1, 2}, longName, EMPTY);
        Archive.write(entries, output);
        Map<String, byte[]> restored = Archive.read(output);
        if (!entries.keySet().equals(restored.keySet())) throw new AssertionError("Round trip changed entry names");
        for (var entry : entries.entrySet()) {
            if (!Arrays.equals(entry.getValue(), restored.get(entry.getKey())))
                throw new AssertionError("Round trip changed entry data");
        }

        byte[] original = Files.readAllBytes(output);
        reject("Unsafe JAR entry name", () -> Archive.write(Map.of("../outside", EMPTY), output));
        String overlongName = "\u00e9".repeat(32_768);
        reject("name exceeds ZIP size limit", () -> Archive.write(Map.of(overlongName, EMPTY), output));
        reject("Invalid UTF-8", () -> Archive.write(Map.of("\ud800", EMPTY), output));
        if (!Arrays.equals(original, Files.readAllBytes(output)))
            throw new AssertionError("Rejected write changed the existing JAR");
        try (var files = Files.list(output.getParent())) {
            if (files.count() != 1) throw new AssertionError("Rejected write left a temporary JAR");
        }
    }

    private static void checkEntryCount(Path atLimit, Path aboveLimit) throws Exception {
        var files = new LinkedHashMap<String, byte[]>();
        for (int i = 0; i < ENTRY_COUNT_LIMIT; i++) files.put("entry" + i, EMPTY);
        Archive.write(files, atLimit);
        if (Archive.read(atLimit).size() != ENTRY_COUNT_LIMIT)
            throw new AssertionError("Archive did not round trip at the count limit");
        files.put("extra", EMPTY);
        reject("too many entries", () -> Archive.write(files, aboveLimit));
        if (Files.exists(aboveLimit)) throw new AssertionError("Rejected write produced a JAR");

        try (var zip = new ZipOutputStream(Files.newOutputStream(aboveLimit))) {
            for (String name : files.keySet()) {
                var entry = new ZipEntry(name);
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(0);
                entry.setCrc(0);
                zip.putNextEntry(entry);
                zip.closeEntry();
            }
        }
        reject("too many entries", () -> Archive.read(aboveLimit));
    }

    private static void reject(String expected, Operation operation) throws Exception {
        try {
            operation.run();
        } catch (IOException failure) {
            if (failure.getMessage().contains(expected)) return;
            throw new AssertionError("Unexpected archive rejection", failure);
        }
        throw new AssertionError("Expected archive rejection containing " + expected);
    }

    @FunctionalInterface
    private interface Operation {
        void run() throws Exception;
    }
}
