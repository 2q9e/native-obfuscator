import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;
import java.util.zip.ZipFile;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Archive-only heuristics. Ground truth is read only after the blind analysis completes. */
public final class ArchiveCheck {
    private ArchiveCheck() {}

    public record ClassMetrics(String name, int bytes, int methods, int fields, int nativeMethods,
                               int javaMethods, String namePattern, String shape,
                               List<String> bodies, List<String> skeletons) {}
    public record Distribution(int count, int min, int p10, int median, int p90, int max,
                               double mean) {}
    public record BlindReport(List<ClassMetrics> classes, Map<String, List<String>> flags,
                              Map<String, Distribution> distributions, Map<String, Integer> reasons) {}
    public record Evaluation(int generated, int genuine, int truePositives, int falsePositives,
                             int falseNegatives, int trueNegatives, List<String> falsePositiveNames,
                             List<String> missedNames, List<String> inventoryNotInArchive) {}

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--self-test")) {
            selfTest();
            return;
        }
        if (args.length < 1 || args.length > 2)
            throw new IllegalArgumentException("Usage: ArchiveCheck FINAL.jar [LOCAL-INVENTORY.txt]");
        BlindReport blind = analyze(Path.of(args[0]));
        var result = new LinkedHashMap<String, Object>();
        result.put("blind", blind);
        if (args.length == 2) result.put("evaluation", evaluate(blind, readInventory(Path.of(args[1]))));
        System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(result));
    }

    public static BlindReport analyze(Path jar) throws IOException {
        var classes = new ArrayList<ClassMetrics>();
        var entries = new HashSet<String>();
        try (var zip = new ZipFile(jar.toFile())) {
            var iterator = zip.entries();
            while (iterator.hasMoreElements()) {
                var entry = iterator.nextElement();
                if (!entries.add(entry.getName())) throw new IOException("Duplicate entry: " + entry.getName());
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                if (entry.getSize() < 0 || entry.getSize() > 16 * 1024 * 1024)
                    throw new IOException("Invalid class size: " + entry.getName());
                byte[] bytes;
                try (var input = zip.getInputStream(entry)) { bytes = input.readNBytes(16 * 1024 * 1024 + 1); }
                if (bytes.length != entry.getSize()) throw new IOException("Class size mismatch: " + entry.getName());
                try {
                    ClassNode node = new ClassNode();
                    new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    var bodies = new ArrayList<String>();
                    var skeletons = new ArrayList<String>();
                    int nativeCount = 0, javaCount = 0;
                    for (MethodNode method : node.methods) {
                        if ((method.access & Opcodes.ACC_NATIVE) != 0) nativeCount++;
                        if (method.instructions.size() == 0) continue;
                        javaCount++;
                        // Constructors and the native registration stub are universal infrastructure.
                        // Six instructions excludes trivial getters, bridges and identity wrappers.
                        if (method.name.startsWith("<") || instructionCount(method) < 6) continue;
                        bodies.add(normalize(method, node.name, false));
                        skeletons.add(normalize(method, node.name, true));
                    }
                    String simple = node.name.substring(node.name.lastIndexOf('/') + 1);
                    String namePattern = simple.replaceAll("[A-Fa-f0-9]{12,}", "@hex@")
                        .replaceAll("[0-9]+", "#");
                    var descriptors = new ArrayList<String>();
                    for (MethodNode method : node.methods)
                        if (!method.name.startsWith("<")) descriptors.add(shapeDescriptor(method.desc, node.name));
                    Collections.sort(descriptors);
                    String shape = node.methods.size() + "/" + node.fields.size() + "/" + nativeCount
                        + "/" + javaCount + "/" + String.join(",", descriptors);
                    // Preserve the archive entry identity, including multi-release variants.
                    String name = entry.getName().substring(0, entry.getName().length() - 6);
                    classes.add(new ClassMetrics(name, bytes.length, node.methods.size(), node.fields.size(),
                        nativeCount, javaCount, namePattern, shape, List.copyOf(bodies), List.copyOf(skeletons)));
                } catch (RuntimeException failure) {
                    throw new IOException("Invalid class: " + entry.getName(), failure);
                }
            }
        }
        classes.sort(Comparator.comparing(ClassMetrics::name));
        var flags = new TreeMap<String, List<String>>();
        groupFlags(classes, ClassMetrics::namePattern, "repeated name pattern", 5, flags);
        groupFlags(classes, ClassMetrics::shape, "uniform class shape", 8, flags);
        groupFlags(classes, c -> Integer.toString(c.bytes() / 32), "repeated 32-byte size band", 12, flags);
        bodyFlags(classes, false, "identical normalized Java body", 5, flags);
        bodyFlags(classes, true, "identical body skeleton with constants erased", 8, flags);
        var distributions = new LinkedHashMap<String, Distribution>();
        summarize(distributions, "all", classes);
        summarize(distributions, "withNativeMethods", classes.stream().filter(c -> c.nativeMethods() > 0).toList());
        summarize(distributions, "withoutNativeMethods", classes.stream().filter(c -> c.nativeMethods() == 0).toList());
        var reasons = new TreeMap<String, Integer>();
        flags.values().forEach(list -> list.forEach(reason -> reasons.merge(reason, 1, Integer::sum)));
        return new BlindReport(List.copyOf(classes), flags, distributions, reasons);
    }

    public static Evaluation evaluate(BlindReport blind, Set<String> generated) {
        var present = new TreeSet<String>();
        blind.classes().forEach(c -> present.add(c.name()));
        var missing = new TreeSet<>(generated);
        missing.removeAll(present);
        var falsePositives = new ArrayList<String>();
        var misses = new ArrayList<String>();
        int tp = 0, tn = 0, actual = 0;
        for (String name : present) {
            boolean truth = generated.contains(name), flagged = blind.flags().containsKey(name);
            if (truth) actual++;
            if (truth && flagged) tp++;
            else if (truth) misses.add(name);
            else if (flagged) falsePositives.add(name);
            else tn++;
        }
        return new Evaluation(actual, present.size() - actual, tp, falsePositives.size(), misses.size(), tn,
            falsePositives, misses, new ArrayList<>(missing));
    }

    public static Set<String> readInventory(Path path) throws IOException {
        var names = new TreeSet<String>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String name = line.strip();
            if (name.isEmpty() || name.startsWith("#")) continue;
            int separator = name.indexOf('#');
            if (separator >= 0) name = name.substring(0, separator);
            if (name.endsWith(".class")) name = name.substring(0, name.length() - 6);
            names.add(name);
        }
        return names;
    }

    private static void groupFlags(List<ClassMetrics> classes, Function<ClassMetrics, String> key,
                                   String reason, int minimum, Map<String, List<String>> flags) {
        var groups = new HashMap<String, Set<String>>();
        for (ClassMetrics c : classes) groups.computeIfAbsent(key.apply(c), unused -> new TreeSet<>()).add(c.name());
        for (var names : groups.values()) if (names.size() >= minimum)
            names.forEach(name -> flag(flags, name, reason));
    }

    private static void bodyFlags(List<ClassMetrics> classes, boolean skeleton, String reason,
                                  int minimum, Map<String, List<String>> flags) {
        var groups = new HashMap<String, Set<String>>();
        for (ClassMetrics c : classes) for (String body : skeleton ? c.skeletons() : c.bodies())
            groups.computeIfAbsent(body, unused -> new TreeSet<>()).add(c.name());
        for (var names : groups.values()) if (names.size() >= minimum)
            names.forEach(name -> flag(flags, name, reason));
    }

    private static void flag(Map<String, List<String>> flags, String name, String reason) {
        List<String> reasons = flags.computeIfAbsent(name, unused -> new ArrayList<>());
        if (!reasons.contains(reason)) reasons.add(reason);
    }

    private static void summarize(Map<String, Distribution> out, String prefix, List<ClassMetrics> classes) {
        out.put(prefix + ".bytes", distribution(classes.stream().mapToInt(ClassMetrics::bytes).toArray()));
        out.put(prefix + ".methods", distribution(classes.stream().mapToInt(ClassMetrics::methods).toArray()));
        out.put(prefix + ".fields", distribution(classes.stream().mapToInt(ClassMetrics::fields).toArray()));
        out.put(prefix + ".nativeMethods", distribution(classes.stream().mapToInt(ClassMetrics::nativeMethods).toArray()));
        out.put(prefix + ".javaMethods", distribution(classes.stream().mapToInt(ClassMetrics::javaMethods).toArray()));
    }

    private static Distribution distribution(int[] values) {
        Arrays.sort(values);
        int n = values.length;
        if (n == 0) return new Distribution(0, 0, 0, 0, 0, 0, 0);
        return new Distribution(n, values[0], values[(n - 1) / 10], values[(n - 1) / 2],
            values[(n - 1) * 9 / 10], values[n - 1], Arrays.stream(values).average().orElse(0));
    }

    private static int instructionCount(MethodNode method) {
        int count = 0;
        for (var instruction : method.instructions) if (instruction.getOpcode() >= 0) count++;
        return count;
    }

    private static String shapeDescriptor(String descriptor, String owner) {
        return descriptor.replace("L" + owner + ";", "Lself;");
    }

    private static String normalize(MethodNode method, String owner, boolean eraseConstants) {
        var text = new StringBuilder(shapeDescriptor(method.desc, owner)).append('|');
        var labels = new IdentityHashMap<LabelNode, Integer>();
        int ordinal = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof LabelNode label) labels.put(label, ordinal);
            if (instruction.getOpcode() >= 0) ordinal++;
        }
        for (var instruction : method.instructions) {
            int op = instruction.getOpcode();
            if (op < 0) continue;
            if (eraseConstants && op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) text.append("iconst");
            else text.append(op);
            if (instruction instanceof VarInsnNode v) text.append(':').append(v.var);
            else if (instruction instanceof IntInsnNode i) text.append(':').append(eraseConstants ? "?" : i.operand);
            else if (instruction instanceof IincInsnNode i) text.append(':').append(i.var).append(':')
                .append(eraseConstants ? "?" : i.incr);
            else if (instruction instanceof LdcInsnNode l) text.append(':').append(l.cst.getClass().getName())
                .append(':').append(eraseConstants ? "?" : l.cst);
            else if (instruction instanceof JumpInsnNode j) text.append(':').append(labels.get(j.label));
            else if (instruction instanceof TypeInsnNode t) text.append(':').append(t.desc.equals(owner) ? "self" : t.desc);
            else if (instruction instanceof FieldInsnNode f) text.append(':').append(f.owner.equals(owner) ? "self" : f.owner)
                .append(":member:").append(shapeDescriptor(f.desc, owner));
            else if (instruction instanceof MethodInsnNode m) text.append(':').append(m.owner.equals(owner) ? "self" : m.owner)
                .append(":member:").append(shapeDescriptor(m.desc, owner)).append(':').append(m.itf);
            else if (instruction instanceof InvokeDynamicInsnNode d) text.append(':').append(d.desc)
                .append(':').append(d.bsm).append(':').append(Arrays.toString(d.bsmArgs));
            else if (instruction instanceof TableSwitchInsnNode s) text.append(':').append(s.min).append(':')
                .append(s.max).append(':').append(labels.get(s.dflt)).append(':')
                .append(s.labels.stream().map(labels::get).toList());
            else if (instruction instanceof LookupSwitchInsnNode s) text.append(':').append(s.keys).append(':')
                .append(labels.get(s.dflt)).append(':').append(s.labels.stream().map(labels::get).toList());
            else if (instruction instanceof MultiANewArrayInsnNode m) text.append(':').append(m.desc).append(':').append(m.dims);
            text.append(';');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static void selfTest() throws Exception {
        Path jar = Files.createTempFile("archive-check-test-", ".jar");
        try {
            try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
                for (int index = 0; index < 15; index++) {
                    String name = index < 14 ? "Utility" + index : "Distinct";
                    ClassWriter writer = new ClassWriter(0);
                    writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
                    if (index < 14) {
                        writer.visitField(0, "value", "I", null, null).visitEnd();
                        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                            "summarize" + index, "(II)I", null, null);
                        method.visitCode();
                        method.visitVarInsn(Opcodes.ILOAD, 0);
                        method.visitIntInsn(Opcodes.SIPUSH, index < 10 ? 3 : index);
                        method.visitInsn(Opcodes.IMUL);
                        method.visitVarInsn(Opcodes.ILOAD, 1);
                        method.visitInsn(Opcodes.IADD);
                        method.visitVarInsn(Opcodes.ILOAD, 0);
                        method.visitInsn(Opcodes.ICONST_2);
                        method.visitInsn(Opcodes.ISHR);
                        method.visitInsn(Opcodes.IXOR);
                        method.visitInsn(Opcodes.IRETURN);
                        method.visitMaxs(3, 2);
                        method.visitEnd();
                    } else writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_NATIVE,
                        "process", "([BII)J", null, null).visitEnd();
                    writer.visitEnd();
                    zip.putNextEntry(new java.util.zip.ZipEntry(name + ".class"));
                    zip.write(writer.toByteArray());
                    zip.closeEntry();
                }
            }
            BlindReport blind = analyze(jar);
            var truth = new TreeSet<String>();
            for (int index = 0; index < 8; index++) truth.add("Utility" + index);
            Evaluation evaluated = evaluate(blind, truth);
            if (evaluated.truePositives() != 8 || evaluated.falsePositives() != 6
                    || evaluated.falseNegatives() != 0 || evaluated.trueNegatives() != 1)
                throw new AssertionError("Evaluation mismatch: " + evaluated);
            if (blind.reasons().getOrDefault("identical normalized Java body", 0) != 10
                    || blind.reasons().getOrDefault("identical body skeleton with constants erased", 0) != 14)
                throw new AssertionError("Constant-sensitive normalization mismatch: " + blind.reasons());
            if (blind.distributions().get("withNativeMethods.bytes").count() != 1)
                throw new AssertionError("Native comparator distribution mismatch");
            System.out.println("ArchiveCheck self-test passed: TP=8 FP=6 FN=0 TN=1; exact=10 skeleton=14");
        } finally { Files.deleteIfExists(jar); }
    }
}
