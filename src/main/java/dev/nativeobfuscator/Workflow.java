package dev.nativeobfuscator;

import dev.nativeobfuscator.translate.ReferenceHierarchy;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.ClassNode;

import static org.objectweb.asm.Opcodes.ACC_NATIVE;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;

final class Workflow {
    private static final Pattern NATIVE_LIBRARY = Pattern.compile(
        "^META-INF/native-obfuscator/(Runtime[0-9a-f]{32})/"
            + "(x86_64-linux|aarch64-linux|x86_64-windows|aarch64-windows)/"
            + "(libprotected\\.so|protected\\.dll)$");
    private static final Pattern REGISTRATION = Pattern.compile(
        "^META-INF/native-obfuscator/(Runtime[0-9a-f]{32})/registration/(0|[1-9][0-9]*)\\.bin$");
    private static final Pattern RUNTIME_CLASS = Pattern.compile(
        "^dev/nativeobfuscator/runtime/(Runtime[0-9a-f]{32})\\.class$");
    private static final int REGISTRATION_LIMIT = 16 * 1024 * 1024;

    private Workflow() {}

    record VerificationReport(boolean structurallyValid, boolean runtimeEquivalenceVerified,
                              String originalSha256, String stagedSha256,
                              int nativeLibraryCount, List<String> nativeTargets,
                              List<String> expectedMethods, List<String> verifiedMethods,
                              List<String> expectedGeneratedClasses, List<String> errors,
                              AnalysisVerification.Report analysis,
                              List<AnalysisNativeVerification.TargetEvidence> nativeHelpers) {
        VerificationReport {
            nativeHelpers = List.copyOf(nativeHelpers);
            nativeTargets = List.copyOf(nativeTargets);
            expectedMethods = List.copyOf(expectedMethods);
            verifiedMethods = List.copyOf(verifiedMethods);
            expectedGeneratedClasses = List.copyOf(expectedGeneratedClasses);
            errors = List.copyOf(errors);
        }
    }

    record PromotionResult(String status, String release, String sha256, String backup) {}

    static VerificationReport verify(Path originalJar, Path stagedJar,
                                     Set<String> expectedNativeMethods,
                                     Set<String> expectedTargets) throws IOException, InterruptedException {
        return verify(originalJar, stagedJar, expectedNativeMethods, expectedTargets, 0, 0);
    }

    static VerificationReport verify(Path originalJar, Path stagedJar,
                                     Set<String> expectedNativeMethods, Set<String> expectedTargets,
                                     int paddingCount, long paddingSeed) throws IOException, InterruptedException {
        return verify(originalJar, stagedJar, expectedNativeMethods, expectedTargets, paddingCount, paddingSeed, 0, 0);
    }

    static VerificationReport verify(Path originalJar, Path stagedJar,
                                     Set<String> expectedNativeMethods, Set<String> expectedTargets,
                                     int paddingCount, long paddingSeed, int markers, long markerSeed)
            throws IOException, InterruptedException {
        return verify(originalJar, stagedJar, expectedNativeMethods, expectedTargets, paddingCount, paddingSeed,
            markers, markerSeed, AnalysisConfig.OFF, null);
    }

    static VerificationReport verify(Path originalJar, Path stagedJar,
                                     Set<String> expectedNativeMethods,
                                     Set<String> expectedTargets,
                                     int paddingCount, long paddingSeed, int markers, long markerSeed,
                                     AnalysisConfig analysis, String clang) throws IOException, InterruptedException {
        return verify(originalJar, stagedJar, expectedNativeMethods, expectedTargets, paddingCount, paddingSeed,
            markers, markerSeed, analysis, clang, List.of());
    }

    static VerificationReport verify(Path originalJar, Path stagedJar,
                                     Set<String> expectedNativeMethods, Set<String> expectedTargets,
                                     int paddingCount, long paddingSeed, int markers, long markerSeed,
                                     AnalysisConfig analysis, String clang, List<Path> hierarchyJars)
            throws IOException, InterruptedException {
        return verify(originalJar, stagedJar, expectedNativeMethods, expectedTargets, paddingCount, paddingSeed,
            markers, markerSeed, analysis, clang, hierarchyJars, Map.of());
    }

    static VerificationReport verify(Path originalJar, Path stagedJar,
                                     Set<String> expectedNativeMethods, Set<String> expectedTargets,
                                     int paddingCount, long paddingSeed, int markers, long markerSeed,
                                     AnalysisConfig analysis, String clang, List<Path> hierarchyJars,
                                     Map<String, String> classPaths)
            throws IOException, InterruptedException {
        if (markers < 0 || markers > 1000) throw new IllegalArgumentException("markers must be between 0 and 1000");
        Path original = regularFile(originalJar, "Original JAR");
        Path staged = regularFile(stagedJar, "Staged JAR");
        if (original.equals(staged))
            throw new IOException("Original and staged JAR must be different files");

        String originalHash = sha256(original);
        String stagedHash = sha256(staged);
        var errors = new ArrayList<String>();
        Map<String, byte[]> originalEntries;
        Map<String, byte[]> stagedEntries;
        Map<String, String> fabric;
        ReferenceHierarchy hierarchy;
        try {
            originalEntries = ClassPaths.remap(Archive.read(original), classPaths);
            fabric = FabricExclusions.from(originalEntries);
            hierarchy = Protector.referenceHierarchy(originalEntries, hierarchyJars);
        } catch (IOException failure) {
            return verification(originalHash, stagedHash, expectedNativeMethods,
                List.of(), 0, List.of(), Set.of(), List.of("Original JAR: " + failure.getMessage()), null, List.of());
        }
        try {
            Archive.checkFileSize(Files.size(staged));
            stagedEntries = analysis.enabled() ? Archive.read(staged, AnalysisVerification.archiveLimits(analysis))
                : Archive.read(staged);
        } catch (IOException failure) {
            return verification(originalHash, stagedHash, expectedNativeMethods,
                List.of(), 0, List.of(), Set.of(), List.of("Staged JAR: " + failure.getMessage()), null, List.of());
        }
        if (!sha256(original).equals(originalHash))
            errors.add("Original JAR changed during verification");
        if (!sha256(staged).equals(stagedHash))
            errors.add("Staged JAR changed during verification");

        var generation = FilePadding.generate(originalEntries, paddingCount, paddingSeed, expectedNativeMethods, hierarchy);
        var utilities = AnalysisDecoys.generate(originalEntries, analysis.decoyMethods(), analysis.seed(),
            generation.classes().keySet());
        var generatedEntries = new LinkedHashMap<>(generation.classes());
        generatedEntries.putAll(utilities.classes());
        var generatedMethods = new HashSet<>(generation.nativeMethods().keySet());
        generatedMethods.addAll(utilities.nativeMethods().keySet());
        for (String name : generatedEntries.keySet()) {
            if (!stagedEntries.containsKey(name)) errors.add("Missing expected generated class " + name);
        }
        Set<String> originalNative = nativeMethods(originalEntries, errors, "Original");
        Set<String> stagedNative = nativeMethods(stagedEntries, errors, "Staged");
        var addedNative = new TreeSet<>(stagedNative);
        addedNative.removeAll(originalNative);
        var expected = new TreeSet<>(expectedNativeMethods);
        if (!expected.containsAll(generatedMethods))
            errors.add("Expected method plan omits required generated native methods");
        if (expected.isEmpty()) errors.add("No eligible methods were supplied for coverage verification");
        if (analysis.enabled() && generatedMethods.containsAll(expected))
            errors.add("Analysis resistance requires a supported application method");
        var expectedByClass = new LinkedHashMap<String, Set<String>>();
        for (String method : expected) {
            int separator = method.indexOf('#');
            if (separator <= 0) {
                errors.add("Invalid expected method signature: " + method);
            } else {
                String path = method.substring(0, separator) + ".class";
                expectedByClass.computeIfAbsent(path, ignored -> new TreeSet<>()).add(method.substring(separator + 1));
                if (generatedEntries.containsKey(path) && !generatedMethods.contains(method))
                    errors.add("Expected method plan includes an unplanned generated native method: " + method);
            }
        }
        var trustedEntries = new LinkedHashMap<>(originalEntries);
        trustedEntries.putAll(generatedEntries);
        var expectedIndices = new LinkedHashMap<String, Integer>();
        for (String entry : trustedEntries.keySet()) {
            if (expectedByClass.containsKey(entry)) expectedIndices.put(entry, expectedIndices.size());
        }
        for (var entry : originalEntries.entrySet()) {
            byte[] updated = stagedEntries.get(entry.getKey());
            if (entry.getKey().equals(DebugMetadata.GRADLE_CACHE)) {
                if (updated != null) errors.add("Unexpected Gradle compiler cache in staged JAR");
            } else if (updated == null) {
                errors.add("Missing original JAR entry " + entry.getKey());
            } else if (!expectedByClass.containsKey(entry.getKey())) {
                byte[] trusted = entry.getValue();
                if (entry.getKey().endsWith(".class")) {
                    try {
                        trusted = DebugMetadata.stripClass(entry.getKey(), trusted, fabric);
                    } catch (IOException invalid) {
                        errors.add("Original JAR: " + invalid.getMessage());
                        continue;
                    }
                }
                if (!Arrays.equals(trusted, updated))
                    errors.add("Unexpected modification to original JAR entry " + entry.getKey());
            }
        }
        for (String method : expected) {
            if (!stagedNative.contains(method))
                errors.add("Selected method is not native in staged JAR: " + method);
            if (!addedNative.contains(method))
                errors.add("Selected method was already native or is absent from the staged JAR: " + method);
        }
        for (String method : addedNative) {
            if (!expected.contains(method))
                errors.add("Unexpected method became native: " + method);
        }

        var runtimeIds = new TreeSet<String>();
        var registrationIds = new TreeSet<String>();
        var libraryRuntimeIds = new TreeSet<String>();
        var registrationResources = new LinkedHashMap<String, Map<Integer, byte[]>>();
        var librariesByTarget = new LinkedHashMap<String, String>();
        int registrationCount = 0;
        for (var entry : stagedEntries.entrySet()) {
            Matcher runtime = RUNTIME_CLASS.matcher(entry.getKey());
            if (runtime.matches()) runtimeIds.add(runtime.group(1));

            Matcher registration = REGISTRATION.matcher(entry.getKey());
            if (registration.matches()) {
                String runtimeId = registration.group(1);
                registrationIds.add(runtimeId);
                registrationCount++;
                if (entry.getValue().length == 0 || entry.getValue().length > REGISTRATION_LIMIT)
                    errors.add("Invalid registration resource size: " + entry.getKey());
                try {
                    int index = Integer.parseInt(registration.group(2));
                    Map<Integer, byte[]> resources =
                        registrationResources.computeIfAbsent(runtimeId, ignored -> new LinkedHashMap<>());
                    if (resources.putIfAbsent(index, entry.getValue()) != null)
                        errors.add("Duplicate registration index " + index + " for " + runtimeId);
                } catch (NumberFormatException failure) {
                    errors.add("Invalid registration index in " + entry.getKey());
                }
            }

            Matcher library = NATIVE_LIBRARY.matcher(entry.getKey());
            if (!library.matches()) {
                if (entry.getKey().startsWith("META-INF/native-obfuscator/")
                    && (entry.getKey().endsWith(".so") || entry.getKey().endsWith(".dll")))
                    errors.add("Unrecognized native library path: " + entry.getKey());
                continue;
            }
            String runtimeId = library.group(1);
            libraryRuntimeIds.add(runtimeId);
            String target = library.group(2);
            String filename = library.group(3);
            if (target.endsWith("-linux") != filename.endsWith(".so"))
                errors.add("Native library name does not match target " + target);
            try {
                NativeImage.executableBytes(entry.getValue(), NativeTarget.parse(target));
            } catch (IOException invalid) {
                errors.add("Invalid native library for " + target + ": " + invalid.getMessage());
            }
            if (librariesByTarget.putIfAbsent(target, entry.getKey()) != null)
                errors.add("Duplicate native library target " + target);
            byte[] sidecar = stagedEntries.get(entry.getKey() + ".sha256");
            if (sidecar == null || sidecar.length != 64) {
                errors.add("Missing or invalid SHA-256 sidecar for " + entry.getKey());
            } else {
                String recorded = new String(sidecar, StandardCharsets.US_ASCII);
                String actual = sha256(entry.getValue());
                if (!recorded.matches("[0-9a-f]{64}") || !recorded.equals(actual))
                    errors.add("Native library checksum mismatch for " + entry.getKey());
            }
            if (!runtimeId.matches("Runtime[0-9a-f]{32}"))
                errors.add("Invalid generated runtime id for " + entry.getKey());
        }

        if (runtimeIds.size() != 1)
            errors.add("Expected exactly one generated native runtime class, found " + runtimeIds.size());
        if (registrationCount == 0)
            errors.add("No native registration resources were found");
        if (librariesByTarget.isEmpty())
            errors.add("No packaged native libraries were found");
        for (String runtimeId : registrationIds) {
            if (!runtimeIds.contains(runtimeId))
                errors.add("Registration resources do not match a generated runtime class");
        }
        for (String runtimeId : libraryRuntimeIds) {
            if (!runtimeIds.contains(runtimeId))
                errors.add("Native libraries do not match a generated runtime class");
        }
        for (String target : expectedTargets) {
            if (!librariesByTarget.containsKey(target))
                errors.add("Missing native library for target " + target);
        }
        for (String target : librariesByTarget.keySet()) {
            if (!expectedTargets.isEmpty() && !expectedTargets.contains(target))
                errors.add("Unexpected packaged native target " + target);
        }

        AnalysisVerification.Report analysisReport = null;
        List<AnalysisNativeVerification.TargetEvidence> nativeHelpers = List.of();
        if (runtimeIds.size() == 1) {
            String runtimeId = runtimeIds.first();
            String loader = "dev/nativeobfuscator/runtime/" + runtimeId;
            String runtimePath = loader + ".class";
            byte[] runtimeBytes = stagedEntries.get(runtimePath);
            if (runtimeBytes == null) {
                errors.add("Generated runtime class is missing: " + runtimePath);
            } else {
                verifyRuntimeClass(runtimeBytes, runtimeId, analysis.enabled(), errors);
                byte[] trustedRuntime = analysis.enabled() ? AnalysisRuntime.loader(loader) : Protector.runtime(loader);
                if (!sameClass(DebugMetadata.stripClass(runtimePath, trustedRuntime, fabric), runtimeBytes))
                    errors.add("Generated runtime class does not match trusted implementation");
                verifyRegistrations(stagedEntries, runtimeId, addedNative,
                    registrationResources, errors);
            }
            String resourceRoot = "META-INF/native-obfuscator/" + runtimeId + "/";
            String manifestClass = AnalysisRuntime.manifestClass(loader) + ".class";
            Set<String> allowedAnalysisResources = Set.of();
            if (analysis.enabled()) {
                byte[] actualManifestClass = stagedEntries.get(manifestClass);
                if (originalEntries.containsKey(manifestClass) || actualManifestClass == null
                    || !sameClass(DebugMetadata.stripClass(manifestClass, AnalysisRuntime.manifest(loader), fabric),
                        actualManifestClass))
                    errors.add("Generated analysis manifest class does not match trusted implementation");
                var verified = AnalysisVerification.verify(analysis, loader, resourceRoot, stagedEntries, librariesByTarget,
                    registrationResources.getOrDefault(runtimeId, Map.of()), utilities, errors);
                analysisReport = verified.report();
                allowedAnalysisResources = verified.allowedEntries();
                nativeHelpers = AnalysisNativeVerification.verify(analysis, originalEntries, expected, runtimeId,
                    stagedEntries, librariesByTarget, errors, clang);
            }
            String markerPath = resourceRoot + "markers.txt";
            byte[] actualMarkers = stagedEntries.get(markerPath);
            if (markers > 0) {
                if (actualMarkers == null) errors.add("Missing expected marker resource " + markerPath);
                else if (!Arrays.equals(actualMarkers, Protector.markers(runtimeId, markers, markerSeed)))
                    errors.add("Marker resource does not match trusted build inputs: " + markerPath);
            }
            for (var ownerPlan : expectedByClass.entrySet()) {
                String classEntry = ownerPlan.getKey();
                byte[] trusted = trustedEntries.get(classEntry);
                if (trusted == null) {
                    errors.add("Expected native owner is absent from trusted build inputs: " + classEntry);
                    continue;
                }
                byte[] actual = stagedEntries.get(classEntry);
                if (actual == null) continue;
                try {
                    byte[] rewritten = Protector.rewriteExpected(trusted,
                        "dev/nativeobfuscator/runtime/" + runtimeId, expectedIndices.get(classEntry), ownerPlan.getValue(), hierarchy);
                    if (!sameClass(DebugMetadata.stripClass(classEntry, rewritten, fabric), actual))
                        errors.add("Selected class does not match trusted build inputs: " + classEntry);
                } catch (IOException | RuntimeException invalid) {
                    errors.add("Selected class does not match trusted build inputs: " + classEntry);
                }
            }
            for (String name : stagedEntries.keySet()) {
                if (originalEntries.containsKey(name) || generatedEntries.containsKey(name)) continue;
                Matcher runtimeClass = RUNTIME_CLASS.matcher(name);
                if (runtimeClass.matches() && runtimeClass.group(1).equals(runtimeId)) continue;
                if (analysis.enabled() && name.equals(manifestClass)) continue;
                boolean knownResource = REGISTRATION.matcher(name).matches()
                    || NATIVE_LIBRARY.matcher(name).matches()
                    || name.endsWith(".sha256") && stagedEntries.containsKey(name.substring(0, name.length() - 7))
                        && NATIVE_LIBRARY.matcher(name.substring(0, name.length() - 7)).matches()
                    || markers > 0 && name.equals(markerPath)
                    || allowedAnalysisResources.contains(name);
                if (!name.startsWith(resourceRoot) || !knownResource)
                    errors.add("Unexpected added JAR entry " + name);
            }
        }

        return verification(originalHash, stagedHash, expected,
            new ArrayList<>(addedNative), librariesByTarget.size(),
            new ArrayList<>(librariesByTarget.keySet()), generatedEntries.keySet(), errors, analysisReport, nativeHelpers);
    }

    private static boolean sameClass(byte[] expected, byte[] actual) {
        try {
            return Arrays.equals(canonicalClass(expected), canonicalClass(actual));
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    private static byte[] canonicalClass(byte[] bytes) {
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        var writer = new org.objectweb.asm.ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    static void publishStage(Path candidate, Path staged, VerificationReport verification) throws IOException {
        if (!verification.structurallyValid())
            throw new IllegalArgumentException("Staged verification did not pass");
        if (!sha256(candidate).equals(verification.stagedSha256()))
            throw new IOException("Candidate JAR changed after staged verification");
        atomicMove(candidate, staged);
    }

    static PromotionResult apply(Path stagedJar, Path releaseJar, Path stateRoot,
                                 boolean confirmed, String verifiedSha256) throws IOException {
        Path staged = regularFile(stagedJar, "Staged JAR");
        Path release = releaseJar.toAbsolutePath().normalize();
        if (staged.equals(release)) throw new IOException("Staged and release JAR must be different files");
        Path parent = release.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Release directory must already exist");
        boolean existed = Files.exists(release, LinkOption.NOFOLLOW_LINKS);
        if (existed && (Files.isSymbolicLink(release)
            || !Files.isRegularFile(release, LinkOption.NOFOLLOW_LINKS)))
            throw new IOException("Release target must be a regular, non-symlink file");
        if (existed && !confirmed)
            return new PromotionResult("confirmation-required", release.toString(), null, null);

        Path state = stateRoot.toAbsolutePath().normalize();
        Files.createDirectories(state);
        if (Files.isSymbolicLink(state) || !Files.isDirectory(state, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("State directory must be a regular, non-symlink directory");
        restrictPermissions(state, true);
        Path stateFile = stateFile(state, release);
        if (Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(stateFile) || !Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Promotion state is not a regular file");
            Properties prior = loadState(stateFile);
            String status = prior.getProperty("status", "");
            if (!status.equals("restored"))
                throw new IOException("A previous promotion is still active; restore it first");
            deleteBackup(state, prior.getProperty("backup", ""));
            Files.delete(stateFile);
        }

        String stagedHash = sha256(staged);
        if (verifiedSha256 == null || !verifiedSha256.matches("[0-9a-f]{64}")
            || !stagedHash.equals(verifiedSha256))
            throw new IOException("Staged JAR changed after verification");
        String priorHash = existed ? sha256(release) : "";
        String backupName = existed ? "backup-" + UUID.randomUUID() + ".jar" : "";
        if (existed) {
            Path backup = state.resolve(backupName);
            Files.copy(release, backup);
            restrictPermissions(backup, false);
            if (!sha256(backup).equals(priorHash)) {
                Files.deleteIfExists(backup);
                throw new IOException("Could not create a consistent release backup");
            }
        }

        Path temporary = Files.createTempFile(parent, ".native-obfuscator-apply-", ".tmp");
        Properties record = new Properties();
        record.setProperty("version", "1");
        record.setProperty("release", release.toString());
        record.setProperty("stagedSha256", stagedHash);
        record.setProperty("previousExists", Boolean.toString(existed));
        record.setProperty("previousSha256", priorHash);
        record.setProperty("backup", backupName);
        record.setProperty("status", "prepared");
        boolean prepared = false;
        boolean promoted = false;
        try {
            Files.copy(staged, temporary, StandardCopyOption.REPLACE_EXISTING);
            if (!sha256(temporary).equals(stagedHash))
                throw new IOException("Staged JAR changed while it was being copied");
            writeState(state, stateFile, record);
            prepared = true;

            if (existed) {
                if (!Files.isRegularFile(release, LinkOption.NOFOLLOW_LINKS)
                    || !sha256(release).equals(priorHash))
                    throw new IOException("Release JAR changed while preparing promotion");
            } else if (Files.exists(release, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Release JAR appeared while preparing promotion");
            }
            atomicMove(temporary, release);
            promoted = true;
            record.setProperty("status", "applied");
            writeState(state, stateFile, record);
            return new PromotionResult("applied", release.toString(), stagedHash, backupName);
        } catch (IOException failure) {
            if (!promoted) {
                if (prepared) Files.deleteIfExists(stateFile);
                if (!backupName.isEmpty()) Files.deleteIfExists(state.resolve(backupName));
            }
            throw failure;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static PromotionResult restore(Path releaseJar, Path stateRoot) throws IOException {
        Path release = releaseJar.toAbsolutePath().normalize();
        Path state = stateRoot.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(state) || !Files.isDirectory(state, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("No promotion state directory exists");
        restrictPermissions(state, true);
        Path stateFile = stateFile(state, release);
        if (Files.isSymbolicLink(stateFile) || !Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("No promotion record exists for " + release);
        Properties record = loadState(stateFile);
        validateRecord(record, release);
        String status = record.getProperty("status", "");
        if (status.equals("restored"))
            throw new IOException("The last promotion has already been restored");

        boolean previousExists = Boolean.parseBoolean(record.getProperty("previousExists", "false"));
        String appliedHash = record.getProperty("stagedSha256", "");
        String previousHash = record.getProperty("previousSha256", "");
        boolean releaseExists = Files.exists(release, LinkOption.NOFOLLOW_LINKS);
        if (releaseExists && (Files.isSymbolicLink(release)
            || !Files.isRegularFile(release, LinkOption.NOFOLLOW_LINKS)))
            throw new IOException("Release target is not a regular, non-symlink file");

        if (status.equals("restoring")) {
            boolean alreadyRestored = previousExists
                ? releaseExists && sha256(release).equals(previousHash)
                : !releaseExists;
            if (alreadyRestored) {
                record.setProperty("status", "restored");
                writeState(state, stateFile, record);
                return new PromotionResult("restored", release.toString(), appliedHash,
                    record.getProperty("backup", ""));
            }
        } else if (!status.equals("applied") && !status.equals("prepared")) {
            throw new IOException("Invalid promotion state");
        }

        if (status.equals("prepared")) {
            boolean promotionNeverStarted = previousExists
                ? releaseExists && !previousHash.isEmpty() && sha256(release).equals(previousHash)
                : !releaseExists;
            if (promotionNeverStarted) {
                record.setProperty("status", "restored");
                writeState(state, stateFile, record);
                return new PromotionResult("restored", release.toString(), appliedHash,
                    record.getProperty("backup", ""));
            }
        }
        if (!releaseExists || !sha256(release).equals(appliedHash))
            throw new IOException("Release JAR changed after promotion; refusing to overwrite it");
        record.setProperty("status", "restoring");
        writeState(state, stateFile, record);

        if (previousExists) {
            Path backup = backupPath(state, record.getProperty("backup", ""));
            if (!Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(backup) || !sha256(backup).equals(previousHash))
                throw new IOException("Promotion backup is missing or changed");
            Path temporary = Files.createTempFile(release.getParent(), ".native-obfuscator-restore-", ".tmp");
            try {
                Files.copy(backup, temporary, StandardCopyOption.REPLACE_EXISTING);
                if (!sha256(temporary).equals(previousHash))
                    throw new IOException("Promotion backup changed while restoring");
                atomicMove(temporary, release);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } else {
            Files.delete(release);
        }
        record.setProperty("status", "restored");
        writeState(state, stateFile, record);
        return new PromotionResult("restored", release.toString(), appliedHash,
            record.getProperty("backup", ""));
    }

    private static VerificationReport verification(String originalHash, String stagedHash,
            Set<String> expectedMethods, List<String> verifiedMethods, int libraryCount,
            List<String> targets, Set<String> generatedClasses, List<String> errors,
            AnalysisVerification.Report analysis, List<AnalysisNativeVerification.TargetEvidence> nativeHelpers) {
        return new VerificationReport(errors.isEmpty(), false, originalHash, stagedHash,
            libraryCount, targets, new ArrayList<>(new TreeSet<>(expectedMethods)),
            verifiedMethods, generatedClasses.stream().sorted().toList(), errors, analysis, nativeHelpers);
    }

    private static void verifyRuntimeClass(byte[] bytes, String runtimeId, boolean analysis,
                                           List<String> errors) {
        ClassNode node = new ClassNode();
        try {
            new ClassReader(bytes).accept(node, 0);
        } catch (RuntimeException failure) {
            errors.add("Generated runtime class is invalid");
            return;
        }
        String expectedName = "dev/nativeobfuscator/runtime/" + runtimeId;
        if (!node.name.equals(expectedName))
            errors.add("Generated runtime class name does not match its JAR path");
        boolean register = node.methods.stream().anyMatch(method ->
            method.name.equals("register") && method.desc.equals("(Ljava/lang/Class;I)V")
                && (method.access & (ACC_PUBLIC | ACC_STATIC)) == (ACC_PUBLIC | ACC_STATIC));
        String nativeName = analysis ? AnalysisRuntime.REGISTER : "register0";
        String descriptor = analysis ? AnalysisRuntime.BOUND_DESCRIPTOR : "(Ljava/lang/Class;I[B[B)V";
        boolean register0 = node.methods.stream().anyMatch(method ->
            method.name.equals(nativeName) && method.desc.equals(descriptor)
                && (method.access & (ACC_STATIC | ACC_NATIVE)) == (ACC_STATIC | ACC_NATIVE));
        if (!register) errors.add("Generated runtime is missing register(Class, int)");
        if (!register0) errors.add("Generated runtime is missing native " + nativeName);
    }

    private static void verifyRegistrations(Map<String, byte[]> entries, String runtimeId,
            Set<String> addedNative, Map<String, Map<Integer, byte[]>> resources,
            List<String> errors) {
        String loader = "dev/nativeobfuscator/runtime/" + runtimeId;
        Map<String, Set<String>> methodsByClass = new TreeMap<>();
        for (String method : addedNative) {
            int separator = method.indexOf('#');
            if (separator <= 0) {
                errors.add("Invalid newly native method signature: " + method);
                continue;
            }
            methodsByClass.computeIfAbsent(method.substring(0, separator),
                ignored -> new TreeSet<>()).add(method.substring(separator + 1));
        }

        Map<Integer, byte[]> runtimeResources = resources.getOrDefault(runtimeId, Map.of());
        Set<Integer> usedIndices = new HashSet<>();
        for (var classEntry : methodsByClass.entrySet()) {
            String owner = classEntry.getKey();
            String classPath = owner + ".class";
            byte[] classBytes = entries.get(classPath);
            if (classBytes == null) continue;
            Integer index = registrationIndex(classBytes, owner, loader, errors);
            if (index == null) continue;
            if (!usedIndices.add(index))
                errors.add("Multiple classes use registration index " + index);
            byte[] metadata = runtimeResources.get(index);
            if (metadata == null) {
                errors.add("Missing registration metadata for " + owner + " at index " + index);
                continue;
            }
            try {
                List<String> registered = registrationMethods(metadata);
                List<String> classOrder = nativeMethodOrder(classBytes, owner,
                    classEntry.getValue(), errors);
                if (!registered.equals(classOrder))
                    errors.add("Registration metadata order does not match native methods in " + owner);
            } catch (IOException failure) {
                errors.add("Invalid registration metadata for " + owner + ": " + failure.getMessage());
            }
        }

        for (var runtimeEntry : resources.entrySet()) {
            if (!runtimeEntry.getKey().equals(runtimeId))
                errors.add("Registration resources do not match the generated runtime class");
            for (int index : runtimeEntry.getValue().keySet()) {
                if (runtimeEntry.getKey().equals(runtimeId) && !usedIndices.contains(index))
                    errors.add("Unused native registration resource at index " + index);
            }
        }
    }

    private static List<String> nativeMethodOrder(byte[] bytes, String owner,
            Set<String> expected, List<String> errors) throws IOException {
        ClassNode node = new ClassNode();
        try {
            new ClassReader(bytes).accept(node, 0);
        } catch (RuntimeException failure) {
            throw new IOException("Invalid selected class " + owner, failure);
        }
        var ordered = new ArrayList<String>();
        var found = new HashSet<String>();
        for (MethodNode method : node.methods) {
            String signature = method.name + method.desc;
            if (!expected.contains(signature)) continue;
            if ((method.access & ACC_NATIVE) == 0)
                errors.add("Registered method is not native in " + owner + ": " + signature);
            ordered.add(signature);
            found.add(signature);
        }
        if (!found.equals(expected))
            errors.add("New native methods do not match classfile methods in " + owner);
        return ordered;
    }

    private static Integer registrationIndex(byte[] bytes, String owner, String loader,
                                             List<String> errors) {
        ClassNode node = new ClassNode();
        try {
            new ClassReader(bytes).accept(node, 0);
        } catch (RuntimeException failure) {
            errors.add("Invalid selected class " + owner);
            return null;
        }
        MethodNode initializer = node.methods.stream()
            .filter(method -> method.name.equals("<clinit>") && method.desc.equals("()V"))
            .findFirst().orElse(null);
        if (initializer == null) {
            errors.add("Selected class has no registration initializer: " + owner);
            return null;
        }

        int matches = 0;
        Integer result = null;
        for (AbstractInsnNode instruction : initializer.instructions) {
            if (!(instruction instanceof MethodInsnNode call)
                || call.getOpcode() != INVOKESTATIC || !call.owner.equals(loader)
                || !call.name.equals("register")
                || !call.desc.equals("(Ljava/lang/Class;I)V")) continue;
            matches++;
            AbstractInsnNode indexInstruction = previousReal(instruction.getPrevious());
            AbstractInsnNode ownerInstruction = indexInstruction == null ? null
                : previousReal(indexInstruction.getPrevious());
            if (!(indexInstruction instanceof LdcInsnNode indexConstant)
                || !(indexConstant.cst instanceof Integer index)
                || !(ownerInstruction instanceof LdcInsnNode ownerConstant)
                || !(ownerConstant.cst instanceof Type ownerType)
                || ownerType.getSort() != Type.OBJECT
                || !ownerType.getInternalName().equals(owner)) {
                errors.add("Invalid native registration arguments in " + owner);
                continue;
            }
            result = index;
        }
        if (matches != 1) {
            errors.add("Expected exactly one native registration call in " + owner);
            return null;
        }
        return result;
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode instruction) {
        while (instruction != null && instruction.getOpcode() < 0)
            instruction = instruction.getPrevious();
        return instruction;
    }

    private static List<String> registrationMethods(byte[] metadata) throws IOException {
        var methods = new ArrayList<String>();
        var seen = new HashSet<String>();
        try (var input = new DataInputStream(new ByteArrayInputStream(metadata))) {
            while (input.available() > 0) {
                String name = input.readUTF();
                if (input.readUnsignedByte() != 0)
                    throw new IOException("Method name terminator is missing");
                String descriptor = input.readUTF();
                if (input.readUnsignedByte() != 0)
                    throw new IOException("Method descriptor terminator is missing");
                String signature = name + descriptor;
                if (name.isEmpty() || descriptor.isEmpty() || !seen.add(signature))
                    throw new IOException("Empty or duplicate method registration");
                methods.add(signature);
            }
        }
        if (methods.isEmpty()) throw new IOException("No methods are registered");
        return List.copyOf(methods);
    }

    private static void restrictPermissions(Path path, boolean directory) throws IOException {
        try {
            Files.setPosixFilePermissions(path,
                PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystems enforce their native permission model.
        }
    }

    private static Set<String> nativeMethods(Map<String, byte[]> entries,
                                             List<String> errors, String label) {
        var methods = new TreeSet<String>();
        for (var entry : entries.entrySet()) {
            String name = entry.getKey();
            if (!name.endsWith(".class") || name.startsWith("META-INF/")
                || name.equals("module-info.class") || RUNTIME_CLASS.matcher(name).matches()) continue;
            ClassNode node = new ClassNode();
            try {
                new ClassReader(entry.getValue()).accept(node, 0);
            } catch (RuntimeException failure) {
                errors.add(label + " JAR has an invalid class " + name);
                continue;
            }
            if (!node.name.equals(name.substring(0, name.length() - 6))) {
                errors.add(label + " class name disagrees with JAR path: " + name);
                continue;
            }
            node.methods.stream().filter(method -> (method.access & ACC_NATIVE) != 0)
                .forEach(method -> methods.add(node.name + "#" + method.name + method.desc));
        }
        return methods;
    }

    private static Path regularFile(Path path, String label) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absolute) || !Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS))
            throw new IOException(label + " must be a regular, non-symlink file: " + absolute);
        return absolute;
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest = Archive.sha256();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[16384];
            for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(Archive.sha256().digest(bytes));
    }

    private static Path stateFile(Path state, Path release) {
        String name = HexFormat.of().formatHex(Archive.sha256()
            .digest(release.toString().getBytes(StandardCharsets.UTF_8)));
        return state.resolve(name + ".properties");
    }

    private static void writeState(Path state, Path stateFile, Properties record) throws IOException {
        Path temporary = Files.createTempFile(state, ".promotion-state-", ".tmp");
        try {
            restrictPermissions(temporary, false);
            try (OutputStream output = Files.newOutputStream(temporary)) {
                record.store(output, "Native Obfuscator artifact promotion");
            }
            atomicMove(temporary, stateFile);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Properties loadState(Path stateFile) throws IOException {
        var record = new Properties();
        try (InputStream input = Files.newInputStream(stateFile)) {
            record.load(input);
        }
        return record;
    }

    private static void validateRecord(Properties record, Path release) throws IOException {
        if (!record.getProperty("version", "").equals("1")
            || !record.getProperty("release", "").equals(release.toString()))
            throw new IOException("Promotion record does not match the requested release path");
    }

    private static Path backupPath(Path state, String name) throws IOException {
        if (!name.matches("backup-[0-9a-fA-F-]{36}\\.jar"))
            throw new IOException("Invalid promotion backup path");
        Path backup = state.resolve(name).normalize();
        if (!backup.getParent().equals(state)) throw new IOException("Invalid promotion backup path");
        return backup;
    }

    private static void deleteBackup(Path state, String name) throws IOException {
        if (!name.isEmpty()) Files.deleteIfExists(backupPath(state, name));
    }

    private static void atomicMove(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
