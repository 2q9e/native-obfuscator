package dev.nativeobfuscator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import static org.objectweb.asm.Opcodes.*;

/** Archive-only mutations of a locally built, padding-disabled JAR. No target execution is needed. */
public final class MarkerWorkflowTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Usage: MarkerWorkflowTest original.jar protected.jar work-dir");
        Path original = Path.of(args[0]);
        var source = Archive.read(original);
        var staged = Archive.read(Path.of(args[1]));
        Path work = Files.createDirectories(Path.of(args[2]));
        Set<String> expected = natives(staged);
        expected.removeAll(natives(source));
        var targets = new TreeSet<String>();
        String runtimeId = null;
        for (String name : staged.keySet()) {
            if (name.startsWith("META-INF/native-obfuscator/") && (name.endsWith(".dll") || name.endsWith(".so"))) {
                String[] parts = name.split("/");
                runtimeId = parts[2];
                targets.add(parts[3]);
            }
        }
        require(runtimeId != null, "No generated runtime");
        Path unchanged = Path.of(args[1]);
        require(Workflow.verify(original, unchanged, expected, targets).structurallyValid(), "Base JAR invalid");
        String markerPath = "META-INF/native-obfuscator/" + runtimeId + "/markers.txt";
        var marked = new LinkedHashMap<>(staged);
        marked.put(markerPath, Protector.markers(runtimeId, 7, 123));
        Path candidate = work.resolve("marked.jar");
        Archive.write(marked, candidate);
        require(Workflow.verify(original, candidate, expected, targets, 0, 0, 7, 123).structurallyValid(), "Trusted marker resource rejected");
        reject(Workflow.verify(original, candidate, expected, targets), "Unexpected markers accepted");
        reject(Workflow.verify(original, candidate, expected, targets, 0, 0, 8, 123), "Wrong marker count accepted");
        reject(Workflow.verify(original, candidate, expected, targets, 0, 0, 7, 124), "Wrong marker seed accepted");
        reject(Workflow.verify(original, unchanged, expected, targets, 0, 0, 7, 123), "Missing markers accepted");
        marked.put(markerPath, "arbitrary payload".getBytes(StandardCharsets.UTF_8));
        Archive.write(marked, candidate);
        reject(Workflow.verify(original, candidate, expected, targets, 0, 0, 7, 123), "Arbitrary marker payload accepted");
        var alteredRuntime = new LinkedHashMap<>(staged);
        String runtimePath = "dev/nativeobfuscator/runtime/" + runtimeId + ".class";
        var node = new ClassNode();
        new ClassReader(alteredRuntime.get(runtimePath)).accept(node, 0);
        node.fields.add(new org.objectweb.asm.tree.FieldNode(ACC_PUBLIC | ACC_STATIC, "unexpected", "I", null, null));
        var writer = new org.objectweb.asm.ClassWriter(0);
        node.accept(writer);
        alteredRuntime.put(runtimePath, writer.toByteArray());
        Archive.write(alteredRuntime, candidate);
        reject(Workflow.verify(original, candidate, expected, targets), "Altered runtime accepted");
        alteredRuntime.put(runtimePath, new byte[] {0, 1, 2, 3});
        Archive.write(alteredRuntime, candidate);
        reject(Workflow.verify(original, candidate, expected, targets), "Malformed runtime accepted");
        System.out.println("Marker/runtime workflow checks passed: " + checks);
    }
    private static Set<String> natives(Map<String, byte[]> entries) {
        var result = new TreeSet<String>();
        entries.forEach((name, bytes) -> {
            if (!name.endsWith(".class") || name.startsWith("dev/nativeobfuscator/runtime/")) return;
            var node = new ClassNode();
            new ClassReader(bytes).accept(node, 0);
            node.methods.stream().filter(m -> (m.access & ACC_NATIVE) != 0)
                .forEach(m -> result.add(node.name + "#" + m.name + m.desc));
        });
        return result;
    }
    private static void reject(Workflow.VerificationReport report, String message) { require(!report.structurallyValid(), message); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
}
