package dev.nativeobfuscator;

import dev.nativeobfuscator.runtime.NativeRuntime;
import dev.nativeobfuscator.translate.MethodCompiler;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.*;

import static org.objectweb.asm.Opcodes.*;

final class Protector {
    private static final String RUNTIME = Type.getInternalName(NativeRuntime.class);
    private static final String RESOURCE = "META-INF/native-obfuscator/";

    record Config(Path input, Path output, List<Pattern> includes, List<Pattern> excludes,
                  List<NativeTarget> targets, String zig, boolean skipUnsupported,
                  boolean analyze, boolean emitC, String clang, String llvmOpt, Path llvmPlugin, String llvmPasses, int maxGrowth, int markers, long markerSeed,
                  boolean padding) {}
    record Decision(String method, String result, String reason) {}
    private record Binding(MethodNode method, String symbol, String c) {}

    static int run(Config config, Consumer<Decision> report) throws IOException, InterruptedException {
        var files = Archive.read(config.input);
        if (files.keySet().stream().anyMatch(name -> name.startsWith(RESOURCE)))
            throw new IOException("Input already uses the native-obfuscator resource namespace");
        String id = runtimeId(files, config);
        String loader = RUNTIME.substring(0, RUNTIME.lastIndexOf('/') + 1) + id;
        var exclusions = FabricExclusions.from(files);
        var versioned = new HashSet<String>();
        for (String name : files.keySet()) {
            if (name.startsWith("META-INF/versions/") && name.endsWith(".class")) {
                int separator = name.indexOf('/', "META-INF/versions/".length());
                if (separator >= 0) versioned.add(name.substring(separator + 1, name.length() - 6));
            }
        }
        var source = new StringBuilder(MethodCompiler.runtimeSupport());
        var registrations = new StringBuilder();
        var outputs = new LinkedHashMap<String, byte[]>();
        int ordinal = 0;
        int unsupported = 0;
        for (var file : files.entrySet()) {
            String entry = file.getKey();
            if (!entry.endsWith(".class") || entry.startsWith("META-INF/") || entry.equals("module-info.class")) continue;
            String owner = entry.substring(0, entry.length() - 6);
            ClassNode node = new ClassNode();
            try {
                new ClassReader(file.getValue()).accept(node, 0);
            } catch (RuntimeException e) {
                throw new IOException("Invalid or unsupported classfile " + entry, e);
            }
            if (!node.name.equals(owner)) throw new IOException("Class name disagrees with JAR path: " + entry);
            String exclusion = exclusions.get(owner);
            if (versioned.contains(owner)) exclusion = "multi-release class";
            if ((node.access & ACC_INTERFACE) != 0) exclusion = "interface class";
            if (isMixin(node.visibleAnnotations) || isMixin(node.invisibleAnnotations)) exclusion = "Mixin class";
            if (node.attrs != null || node.methods.stream().anyMatch(m -> m.attrs != null)
                || node.fields.stream().anyMatch(f -> f.attrs != null)
                || (node.recordComponents != null && node.recordComponents.stream().anyMatch(r -> r.attrs != null)))
                exclusion = "custom classfile attributes";
            var bindings = new ArrayList<Binding>();
            for (MethodNode method : node.methods) {
                String name = owner + "#" + method.name + method.desc;
                if (!matches(config.includes, name)) continue;
                if (matches(config.excludes, name)) {
                    report.accept(new Decision(name, "excluded", "matched --exclude"));
                } else if (exclusion != null) {
                    report.accept(new Decision(name, "excluded", exclusion));
                } else {
                    String symbol = GeneratedNames.forSymbol("method_", id, name);
                    try {
                        int sort = Type.getReturnType(method.desc).getSort();
                        if (sort == Type.ARRAY || sort == Type.OBJECT)
                            throw new MethodCompiler.Unsupported("reference-return assignability requires JVM resolution");
                        String c = MethodCompiler.compile(node, method, symbol);
                        bindings.add(new Binding(method, symbol, c));
                        ordinal++;
                        report.accept(new Decision(name, config.analyze ? "eligible" : "selected", ""));
                    } catch (MethodCompiler.Unsupported failure) {
                        unsupported++;
                        report.accept(new Decision(name, config.skipUnsupported ? "retained" : "rejected", failure.getMessage()));
                    }
                }
            }
            if (!bindings.isEmpty() && !config.analyze) {
                appendRegistrations(source, registrations, bindings, outputs.size());
                outputs.put(entry, rewrite(node, loader, outputs.size(), bindings));
            }
        }
        if (unsupported != 0 && !config.skipUnsupported)
            throw new IOException(unsupported + " selected methods require JVM bytecode; see method diagnostics");
        if (config.analyze) return ordinal;
        if (outputs.isEmpty()) throw new IOException("No supported methods were selected");
        if (files.containsKey(loader + ".class")) throw new IOException("Injected runtime path collides with input");
        source.append("JNIEXPORT void JNICALL Java_").append(jniMangle(loader))
            .append("_register(JNIEnv *env, jclass runtime, jclass owner, jint index) {\n")
            .append("    (void)runtime;\n    switch (index) {\n").append(registrations)
            .append("    default: break;\n    }\n")
            .append("    if (!(*env)->ExceptionCheck(env)) {\n")
            .append("        jclass error = (*env)->FindClass(env, \"java/lang/UnsatisfiedLinkError\");\n")
            .append("        if (error != NULL) (*env)->ThrowNew(env, error, \"Native method registration failed\");\n")
            .append("    }\n}\n");
        if (config.emitC) {
            Files.writeString(config.output, source);
            return ordinal;
        }
        Path workspace = Files.createTempDirectory("native-obfuscator-build-");
        try {
            Path c = Files.writeString(workspace.resolve("translated.c"), source);
            files.putAll(outputs);
            files.put(loader + ".class", runtime(loader));
            for (NativeTarget target : config.targets) {
                byte[] binary = target.compile(workspace, c, config);
                String base = RESOURCE + id + "/" + target.key + "/" + target.file;
                files.put(base, binary);
                files.put(base + ".sha256", HexFormat.of().formatHex(Archive.sha256().digest(binary))
                    .getBytes(StandardCharsets.US_ASCII));
            }
            if (config.markers > 0) {
                files.put(RESOURCE + id + "/markers.txt", markers(id, config.markers, config.markerSeed));
            }
            if (config.padding) {
                FilePadding.bundle(files, RESOURCE + id + "/", id);
            }
            Archive.write(files, config.output);
        } finally {
            try (var paths = Files.walk(workspace)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        return ordinal;
    }

    private static byte[] markers(String id, int count, long seed) {
        var markerText = new StringBuilder();
        var input = ByteBuffer.allocate(Long.BYTES + Integer.BYTES);
        var digest = Archive.sha256();
        byte[] build = id.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < count; i++) {
            input.clear();
            input.putLong(seed).putInt(i);
            digest.update(build);
            markerText.append("canary-").append(HexFormat.of().formatHex(digest.digest(input.array())))
                .append('\n');
        }
        return markerText.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static String runtimeId(Map<String, byte[]> files, Config config) {
        var digest = Archive.sha256();
        files.forEach((name, data) -> {
            byte[] key = name.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(8).putInt(key.length).putInt(data.length).array());
            digest.update(key);
            digest.update(data);
        });
        digest.update((config.includes + "\n" + config.excludes + "\n" + config.targets
            + "\n" + config.llvmPasses + "\nformat 1").getBytes(StandardCharsets.UTF_8));
        return "Runtime" + HexFormat.of().formatHex(digest.digest(), 0, 16);
    }

    private static boolean matches(List<Pattern> patterns, String method) {
        return patterns.stream().anyMatch(pattern -> pattern.matcher(method).matches());
    }

    private static boolean isMixin(List<AnnotationNode> annotations) {
        return annotations != null && annotations.stream()
            .anyMatch(annotation -> annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;"));
    }

    private static byte[] rewrite(ClassNode node, String loader, int id, List<Binding> bindings) {
        for (Binding binding : bindings) {
            MethodNode method = binding.method;
            method.access |= ACC_NATIVE;
            method.instructions.clear();
            method.localVariables = null;
            method.visibleLocalVariableAnnotations = null;
            method.invisibleLocalVariableAnnotations = null;
        }
        MethodNode init = node.methods.stream().filter(method -> method.name.equals("<clinit>"))
            .findFirst().orElseGet(() -> {
                MethodNode method = new MethodNode(ACC_STATIC, "<clinit>", "()V", null, null);
                method.instructions.add(new InsnNode(RETURN));
                node.methods.add(method);
                return method;
            });
        InsnList register = new InsnList();
        register.add(new LdcInsnNode(Type.getObjectType(node.name)));
        register.add(new LdcInsnNode(id));
        register.add(new MethodInsnNode(INVOKESTATIC, loader, "register", "(Ljava/lang/Class;I)V", false));
        init.instructions.insert(register);
        init.maxStack = Math.max(init.maxStack, 2);
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static byte[] runtime(String loader) throws IOException {
        try (InputStream input = NativeRuntime.class.getResourceAsStream("/" + RUNTIME + ".class")) {
            if (input == null) throw new IOException("Tool JAR is missing its runtime class");
            var writer = new ClassWriter(0);
            new ClassReader(input).accept(new ClassRemapper(writer, new SimpleRemapper(ASM9, Map.of(RUNTIME, loader))), 0);
            return writer.toByteArray();
        }
    }

    private static void appendRegistrations(StringBuilder source, StringBuilder dispatch,
                                            List<Binding> bindings, int index) throws IOException {
        for (Binding binding : bindings) source.append(binding.c);
        source.append("static const JNINativeMethod class_").append(index).append("[] = {\n");
        for (Binding binding : bindings) {
            source.append("    {").append(cString(binding.method.name)).append(", ")
                .append(cString(binding.method.desc)).append(", (void *)").append(binding.symbol).append("},\n");
        }
        source.append("};\n\n");
        dispatch.append("    case ").append(index).append(":\n")
            .append("        if ((*env)->RegisterNatives(env, owner, class_").append(index).append(", ")
            .append(bindings.size()).append(") == JNI_OK) return;\n")
            .append("        break;\n");
    }

    private static String cString(String value) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) { out.writeUTF(value); }
        byte[] encoded = bytes.toByteArray();
        var escaped = new StringBuilder("\"");
        for (int i = 2; i < encoded.length; i++) {
            int b = Byte.toUnsignedInt(encoded[i]);
            escaped.append('\\').append((b >>> 6) & 7).append((b >>> 3) & 7).append(b & 7);
        }
        return escaped.append('"').toString();
    }

    private static String jniMangle(String name) {
        return name.replace("_", "_1").replace('/', '_');
    }
}
