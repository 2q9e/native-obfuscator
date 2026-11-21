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
import java.util.Set;
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
                  int paddingCount, long paddingSeed, int minInstructions, boolean reportNativeSizes) {}
    record Decision(String method, String result, String reason, Integer instructions,
                    Integer branches, String target, Long nativeBytes) {
        Decision(String method, String result, String reason) {
            this(method, result, reason, null, null, null, null);
        }

        Decision(String method, String result, String reason, MethodMetrics metrics) {
            this(method, result, reason, metrics.instructions, metrics.branches, null, null);
        }
    }

    private record MethodMetrics(int instructions, int branches) {}
    private record Binding(MethodNode method, String symbol, String c) {}

    static int run(Config config, Consumer<Decision> report) throws IOException, InterruptedException {
        var files = Archive.read(config.input);
        if (files.keySet().stream().anyMatch(name -> name.startsWith(RESOURCE)))
            throw new IOException("Input already uses the native-obfuscator resource namespace");
        var exclusions = FabricExclusions.from(files);
        var versioned = versionedClasses(files.keySet());
        var generated = FilePadding.generate(files, config.paddingCount, config.paddingSeed,
            config.paddingCount == 0 ? Set.of() : paddingReferencePlan(files, config, exclusions, versioned));
        files.putAll(generated.classes());
        String id = runtimeId(files, config);
        String loader = RUNTIME.substring(0, RUNTIME.lastIndexOf('/') + 1) + id;
        var source = new StringBuilder(MethodCompiler.runtimeSupport()).append(registrationSupport());
        var registrationResources = new LinkedHashMap<String, byte[]>();
        var registrations = new StringBuilder();
        var outputs = new LinkedHashMap<String, byte[]>();
        var selectedSymbols = new LinkedHashMap<String, String>();
        int ordinal = 0;
        int unsupported = 0;
        for (var file : files.entrySet()) {
            String entry = file.getKey();
            if (!applicationClass(entry)) continue;
            ClassNode node = readClass(entry, file.getValue());
            String owner = node.name;
            String exclusion = classExclusion(node, exclusions, versioned);
            var bindings = new ArrayList<Binding>();
            boolean generatedClass = generated.classes().containsKey(entry);
            for (MethodNode method : node.methods) {
                String name = owner + "#" + method.name + method.desc;
                boolean required = generated.nativeMethods().containsKey(name);
                if (!required && (generatedClass || !matches(config.includes, name))) continue;
                if (!required && matches(config.excludes, name)) {
                    report.accept(new Decision(name, "excluded", "matched --exclude"));
                } else if (exclusion != null) {
                    report.accept(new Decision(name, "excluded", exclusion));
                } else {
                    String symbol = GeneratedNames.nativeFunction(id, name);
                    try {
                        String c = compile(node, method, symbol);
                        MethodMetrics metrics = measure(method);
                        if (!required && metrics.instructions < config.minInstructions) {
                            report.accept(new Decision(name, "excluded",
                                "below minimum JVM instruction count " + config.minInstructions, metrics));
                            continue;
                        }
                        bindings.add(new Binding(method, symbol, c));
                        if (!config.analyze && config.llvmPlugin == null)
                            System.err.println("native-obfuscator: method=" + name + " function=" + symbol
                                + " profile=compatibility transformed=0");
                        if (!config.analyze) selectedSymbols.put(symbol, name);
                        ordinal++;
                        report.accept(new Decision(name, config.analyze ? "eligible" : "selected", "", metrics));
                    } catch (MethodCompiler.Unsupported failure) {
                        if (required) throw new IOException("Generated utility method cannot be converted: " + name
                            + ": " + failure.getMessage());
                        unsupported++;
                        report.accept(new Decision(name, config.skipUnsupported ? "retained" : "rejected", failure.getMessage()));
                    }
                }
            }
            if (generatedClass && bindings.isEmpty())
                throw new IOException("Generated class has zero converted methods: " + owner);
            if (!bindings.isEmpty() && !config.analyze) {
                int index = outputs.size();
                byte[] metadata = registrationMetadata(bindings);
                appendRegistrations(source, registrations, bindings, index, metadata);
                registrationResources.put(RESOURCE + id + "/registration/" + index + ".bin", metadata);
                outputs.put(entry, rewrite(node, loader, index, bindings));
            }
        }
        if (unsupported != 0 && !config.skipUnsupported)
            throw new IOException(unsupported + " selected methods require JVM bytecode; see method diagnostics");
        if (config.analyze) return ordinal;
        if (outputs.isEmpty()) throw new IOException("No supported methods were selected");
        if (files.containsKey(loader + ".class")) throw new IOException("Injected runtime path collides with input");
        source.append("JNIEXPORT void JNICALL Java_").append(jniMangle(loader))
            .append("_register0(JNIEnv *env, jclass runtime, jclass owner, jint index, jbyteArray metadata, jbyteArray digest) {\n")
            .append("    (void)runtime;\n")
            .append("    unsigned char actual_digest[32];\n")
            .append("    (*env)->GetByteArrayRegion(env, digest, 0, 32, (jbyte *)actual_digest);\n")
            .append("    if ((*env)->ExceptionCheck(env)) return;\n")
            .append("    switch (index) {\n").append(registrations)
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
            files.putAll(registrationResources);
            files.put(loader + ".class", runtime(loader));
            var nativeSizes = new ArrayList<Decision>();
            for (NativeTarget target : config.targets) {
                Consumer<Map<String, Long>> sizes = config.reportNativeSizes
                    ? measured -> selectedSymbols.forEach((symbol, method) -> nativeSizes.add(
                        new Decision(method, "native-size", measured.containsKey(symbol) ? "" : "no linked symbol",
                            null, null, target.key, measured.get(symbol))))
                    : null;
                byte[] binary = target.compile(workspace, c, config, selectedSymbols, sizes);
                String base = RESOURCE + id + "/" + target.key + "/" + target.file;
                files.put(base, binary);
                files.put(base + ".sha256", HexFormat.of().formatHex(Archive.sha256().digest(binary))
                    .getBytes(StandardCharsets.US_ASCII));
            }
            if (config.markers > 0) {
                files.put(RESOURCE + id + "/markers.txt", markers(id, config.markers, config.markerSeed));
            }
            Archive.write(files, config.output);
            nativeSizes.forEach(report);
        } finally {
            try (var paths = Files.walk(workspace)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        return ordinal;
    }

    static byte[] markers(String id, int count, long seed) {
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
            + "\n" + config.llvmPasses + "\nformat 2").getBytes(StandardCharsets.UTF_8));
        if (config.minInstructions != 0)
            digest.update(("minimum instructions " + config.minInstructions).getBytes(StandardCharsets.UTF_8));
        return "Runtime" + HexFormat.of().formatHex(digest.digest(), 0, 16);
    }

    private static MethodMetrics measure(MethodNode method) {
        int instructions = 0;
        int branches = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            int opcode = instruction.getOpcode();
            if (opcode < 0) continue;
            instructions++;
            if (instruction instanceof TableSwitchInsnNode || instruction instanceof LookupSwitchInsnNode
                || (instruction instanceof JumpInsnNode && opcode != GOTO && opcode != JSR))
                branches++;
        }
        return new MethodMetrics(instructions, branches);
    }

    /** Resolve the genuine native selection before sampling final class structures. */
    private static Set<String> paddingReferencePlan(Map<String, byte[]> files, Config config,
            Map<String, String> exclusions, Set<String> versioned) throws IOException {
        var selected = new HashSet<String>();
        for (var file : files.entrySet()) {
            String path = file.getKey();
            if (!applicationClass(path)) continue;
            ClassNode node = readClass(path, file.getValue());
            if (classExclusion(node, exclusions, versioned) != null) continue;
            for (MethodNode method : node.methods) {
                String signature = node.name + "#" + method.name + method.desc;
                if (!matches(config.includes, signature) || matches(config.excludes, signature)) continue;
                try {
                    compile(node, method, "reference_plan");
                    if (measure(method).instructions >= config.minInstructions) selected.add(signature);
                } catch (MethodCompiler.Unsupported ignored) {
                    // The main plan reports the precise unsupported-policy decision.
                }
            }
        }
        return Set.copyOf(selected);
    }

    private static boolean applicationClass(String path) {
        return path.endsWith(".class") && !path.startsWith("META-INF/") && !path.equals("module-info.class");
    }

    private static ClassNode readClass(String path, byte[] bytes) throws IOException {
        var node = new ClassNode();
        try {
            new ClassReader(bytes).accept(node, 0);
        } catch (RuntimeException failure) {
            throw new IOException("Invalid or unsupported classfile " + path, failure);
        }
        if (!path.equals(node.name + ".class")) throw new IOException("Class name disagrees with JAR path: " + path);
        return node;
    }

    private static Set<String> versionedClasses(Set<String> paths) {
        var versioned = new HashSet<String>();
        String prefix = "META-INF/versions/";
        for (String path : paths) {
            if (!path.startsWith(prefix) || !path.endsWith(".class")) continue;
            int separator = path.indexOf('/', prefix.length());
            if (separator >= 0) versioned.add(path.substring(separator + 1, path.length() - 6));
        }
        return versioned;
    }

    private static String classExclusion(ClassNode node, Map<String, String> exclusions, Set<String> versioned) {
        if (node.attrs != null || node.methods.stream().anyMatch(m -> m.attrs != null)
            || node.fields.stream().anyMatch(f -> f.attrs != null)
            || (node.recordComponents != null && node.recordComponents.stream().anyMatch(r -> r.attrs != null)))
            return "custom classfile attributes";
        if (isMixin(node.visibleAnnotations) || isMixin(node.invisibleAnnotations)) return "Mixin class";
        if ((node.access & ACC_INTERFACE) != 0) return "interface class";
        if (versioned.contains(node.name)) return "multi-release class";
        return exclusions.get(node.name);
    }

    private static String compile(ClassNode node, MethodNode method, String symbol) throws MethodCompiler.Unsupported {
        int sort = Type.getReturnType(method.desc).getSort();
        if (sort == Type.ARRAY || sort == Type.OBJECT)
            throw new MethodCompiler.Unsupported("reference-return assignability requires JVM resolution");
        return MethodCompiler.compile(node, method, symbol);
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

    static byte[] rewriteExpected(byte[] original, String loader, int index,
                                  java.util.Set<String> methodSignatures) throws IOException {
        ClassNode node = new ClassNode();
        new ClassReader(original).accept(node, 0);
        var bindings = new ArrayList<Binding>();
        for (MethodNode method : node.methods) {
            if (methodSignatures.contains(method.name + method.desc))
                bindings.add(new Binding(method, "", ""));
        }
        if (bindings.size() != methodSignatures.size())
            throw new IOException("Expected native method is absent from trusted class " + node.name);
        return rewrite(node, loader, index, bindings);
    }

    static byte[] runtime(String loader) throws IOException {
        try (InputStream input = NativeRuntime.class.getResourceAsStream("/" + RUNTIME + ".class")) {
            if (input == null) throw new IOException("Tool JAR is missing its runtime class");
            var writer = new ClassWriter(0);
            new ClassReader(input).accept(new ClassRemapper(writer, new SimpleRemapper(ASM9, Map.of(RUNTIME, loader))), 0);
            return writer.toByteArray();
        }
    }

    private static byte[] registrationMetadata(List<Binding> bindings) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            for (Binding binding : bindings) {
                out.writeUTF(binding.method.name);
                out.writeByte(0);
                out.writeUTF(binding.method.desc);
                out.writeByte(0);
                if (bytes.size() > NativeRuntime.MAX_REGISTRATION_BYTES)
                    throw new IOException("Native registration metadata exceeds size limit");
            }
        }
        return bytes.toByteArray();
    }

    private static void appendRegistrations(StringBuilder source, StringBuilder dispatch,
                                            List<Binding> bindings, int index, byte[] metadata) {
        for (Binding binding : bindings) source.append(binding.c);
        source.append("static void *const class_").append(index).append("[] = {\n");
        for (Binding binding : bindings) {
            source.append("    (void *)").append(binding.symbol).append(",\n");
        }
        source.append("};\n");
        var digest = HexFormat.ofDelimiter(", ").withPrefix("0x").formatHex(Archive.sha256().digest(metadata));
        source.append("static const unsigned char class_digest_").append(index)
            .append("[] = {").append(digest).append("};\n\n");
        dispatch.append("    case ").append(index).append(":\n")
            .append("        if (memcmp(actual_digest, class_digest_").append(index).append(", 32) == 0")
            .append(" && register_methods(env, owner, metadata, class_").append(index).append(", ")
            .append(bindings.size()).append(") == JNI_OK) return;\n")
            .append("        break;\n");
    }

    private static String registrationSupport() {
        return """
            #include <stdlib.h>

            static char *registration_string(unsigned char **cursor, const unsigned char *end) {
                unsigned char *field = *cursor;
                size_t remaining = (size_t)(end - field);
                if (remaining < 3) return NULL;
                size_t length = ((size_t)field[0] << 8) | field[1];
                if (length == 0 || length > remaining - 3 || field[2 + length] != 0
                        || memchr(field + 2, 0, length) != NULL) return NULL;
                *cursor = field + length + 3;
                return (char *)(field + 2);
            }

            static jint register_methods(JNIEnv *env, jclass owner, jbyteArray metadata,
                                         void *const *functions, jint count) {
                JNINativeMethod *methods = calloc((size_t)count, sizeof *methods);
                if (methods == NULL) return JNI_ERR;
                jsize length = (*env)->GetArrayLength(env, metadata);
                jbyte *data = (*env)->GetByteArrayElements(env, metadata, NULL);
                if (data == NULL) {
                    free(methods);
                    return JNI_ERR;
                }
                unsigned char *cursor = (unsigned char *)data;
                const unsigned char *end = cursor + length;
                jint status = JNI_ERR;
                for (jint i = 0; i < count; ++i) {
                    methods[i].name = registration_string(&cursor, end);
                    methods[i].signature = registration_string(&cursor, end);
                    if (methods[i].name == NULL || methods[i].signature == NULL) goto cleanup;
                    methods[i].fnPtr = functions[i];
                }
                if (cursor == end) status = (*env)->RegisterNatives(env, owner, methods, count);
            cleanup:
                (*env)->ReleaseByteArrayElements(env, metadata, data, JNI_ABORT);
                free(methods);
                return status;
            }

            """;
    }

    private static String jniMangle(String name) {
        return name.replace("_", "_1").replace('/', '_');
    }
}
