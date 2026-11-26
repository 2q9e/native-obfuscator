package dev.nativeobfuscator;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import static org.objectweb.asm.Opcodes.ACC_NATIVE;
import static org.objectweb.asm.Opcodes.ASM9;

final class ClassPaths {
    private static final int MAX_MAP_BYTES = 1024 * 1024;
    private static final String IDENTIFIER = "\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*";
    private static final Pattern NAME = Pattern.compile(IDENTIFIER + "(?:/" + IDENTIFIER + ")*");
    private static final String SERVICES = "META-INF/services/";

    private ClassPaths() {}

    static Map<String, String> read(Path path) throws IOException {
        byte[] contents;
        try (var input = Files.newInputStream(path)) {
            contents = input.readNBytes(MAX_MAP_BYTES + 1);
        }
        if (contents.length > MAX_MAP_BYTES) throw new IOException("Class map exceeds size limit");
        var aliases = new LinkedHashMap<String, String>();
        var targets = new HashSet<String>();
        int number = 0;
        String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(contents)).toString();
        for (String raw : text.lines().toList()) {
            number++;
            String line = raw.split("#", 2)[0].strip();
            if (line.isEmpty()) continue;
            String[] pair = line.split("=", -1);
            if (pair.length != 2) throw new IOException("Expected old/name=new/name at class map line " + number);
            String original = pair[0].strip();
            String alias = pair[1].strip();
            if (!validName(original) || !validName(alias) || original.equals(alias))
                throw new IOException("Invalid class mapping at line " + number);
            if (aliases.putIfAbsent(original, alias) != null || !targets.add(alias))
                throw new IOException("Duplicate class mapping or target at line " + number);
        }
        if (aliases.isEmpty()) throw new IOException("Class map is empty");
        return Map.copyOf(aliases);
    }

    private static boolean validName(String name) {
        String checked = simpleName(name).equals("package-info") ? packageName(name) + "P" : name;
        return name.length() <= 1024 && NAME.matcher(checked).matches()
            && !name.startsWith("java/") && !name.startsWith("dev/nativeobfuscator/runtime/");
    }

    static Map<String, byte[]> remap(Map<String, byte[]> files, Map<String, String> aliases) throws IOException {
        if (aliases.isEmpty()) return files;
        var names = new HashSet<String>();
        for (String entry : files.keySet()) {
            if (!entry.endsWith(".class")) continue;
            if (entry.equals("module-info.class") || entry.startsWith("META-INF/"))
                throw new IOException("Class maps require an ordinary, non-modular JAR with one version of each class");
            names.add(entry.substring(0, entry.length() - ".class".length()));
        }
        var keep = externalNames(files);
        for (var entry : aliases.entrySet()) {
            String original = entry.getKey();
            String alias = entry.getValue();
            if (simpleName(original).equals("package-info") != simpleName(alias).equals("package-info"))
                throw new IOException("Package metadata must retain its package-info class name: " + original);
            if (!names.contains(original)) throw new IOException("Class map source is absent: " + original);
            if (names.contains(alias)) throw new IOException("Class map collides with an existing class: " + alias);
            if (keep.contains(original)) throw new IOException("Class map changes an externally registered class: " + original);
        }
        Set<String> relocatedPackages = checkPackages(names, aliases);
        for (String path : files.keySet()) {
            if (!path.endsWith(".class") && !path.endsWith("/") && !path.startsWith("META-INF/")
                && hasRelocatedDirectory(path, relocatedPackages))
                throw new IOException("Class map relocates a package containing a resource: " + path);
        }
        var enclosers = new HashMap<String, String>();
        for (String original : aliases.keySet()) {
            ClassNode node = readClass(original + ".class", files.get(original + ".class"), ClassReader.SKIP_CODE);
            if (node.outerClass != null) enclosers.put(original, node.outerClass);
        }
        var dottedNames = new HashSet<String>();
        aliases.keySet().forEach(name -> dottedNames.add(name.replace('/', '.')));
        var remapper = new SimpleRemapper(ASM9, aliases) {
            @Override public String mapInnerClassName(String name, String owner, String innerName) {
                String alias = aliases.get(name);
                if (alias == null || innerName == null) return innerName;
                String outer = owner == null ? enclosers.get(name) : owner;
                if (outer == null) throw new IllegalArgumentException("Missing enclosing class for " + name);
                checkEnclosing(name, outer, aliases);
                String suffix = alias.substring(aliases.getOrDefault(outer, outer).length() + 1);
                if (owner == null) suffix = suffix.replaceFirst("^[0-9]+", "");
                if (suffix.isEmpty()) throw new IllegalArgumentException("Missing inner name for " + alias);
                return suffix;
            }

            @Override public Object mapValue(Object value) {
                // A string can be a reflection or loader contract. The class map
                // only rewrites typed class references.
                if (value instanceof String text && (aliases.containsKey(text) || dottedNames.contains(text)
                    || descriptorMentionsAlias(text, aliases)))
                    throw new IllegalArgumentException("Literal class reference " + text);
                return super.mapValue(value);
            }
        };
        var output = new LinkedHashMap<String, byte[]>();
        for (var file : files.entrySet()) {
            String path = file.getKey();
            if (!path.endsWith(".class")) {
                output.put(path, file.getValue());
                continue;
            }
            try {
                var node = readClass(path, file.getValue(), 0);
                String alias = aliases.getOrDefault(node.name, node.name);
                checkMetadata(node, aliases, remapper);
                if (!alias.equals(node.name)) {
                    if (node.sourceFile != null) node.sourceFile = simpleName(alias).split("\\$", 2)[0] + ".java";
                    node.sourceDebug = null;
                }
                var writer = new ClassWriter(0);
                node.accept(new ClassRemapper(writer, remapper));
                output.put(alias + ".class", writer.toByteArray());
            } catch (RuntimeException failure) {
                throw new IOException("Cannot remap " + path + ": " + failure.getMessage(), failure);
            }
        }
        removeEmptyRelocatedDirectories(output, relocatedPackages);
        return output;
    }

    private static ClassNode readClass(String path, byte[] bytes, int flags) throws IOException {
        var node = new ClassNode();
        try {
            new ClassReader(bytes).accept(node, flags);
        } catch (RuntimeException failure) {
            throw new IOException("Invalid classfile " + path, failure);
        }
        if (!path.equals(node.name + ".class")) throw new IOException("Class name disagrees with JAR path: " + path);
        return node;
    }

    private static void removeEmptyRelocatedDirectories(Map<String, byte[]> files, Set<String> relocated) {
        var empty = new HashSet<String>();
        for (String owner : relocated)
            for (String path = owner; !path.isEmpty(); path = packageName(path.substring(0, path.length() - 1)))
                empty.add(path);
        if (empty.isEmpty()) return;
        for (String entry : files.keySet()) {
            // Keep the directory if any surviving file or unrelated directory is inside it.
            if (empty.contains(entry)) continue;
            String name = entry.endsWith("/") ? entry.substring(0, entry.length() - 1) : entry;
            for (String path = packageName(name); !path.isEmpty(); path = packageName(path.substring(0, path.length() - 1)))
                empty.remove(path);
        }
        empty.forEach(files::remove);
    }

    private static boolean hasRelocatedDirectory(String name, Set<String> relocated) {
        String path = packageName(name);
        while (!path.isEmpty()) {
            if (relocated.contains(path)) return true;
            path = packageName(path.substring(0, path.length() - 1));
        }
        return relocated.contains("");
    }

    private static boolean descriptorMentionsAlias(String value, Map<String, String> aliases) {
        if (value.isEmpty() || "L[(".indexOf(value.charAt(0)) < 0 || value.indexOf(';') < 0) return false;
        for (int i = value.indexOf('L'); i >= 0; ) {
            int end = value.indexOf(';', i + 1);
            if (end < 0) return false;
            if (aliases.containsKey(value.substring(i + 1, end))) return true;
            i = value.indexOf('L', end + 1);
        }
        return false;
    }

    private static Set<String> checkPackages(Set<String> names, Map<String, String> aliases) throws IOException {
        var existing = new HashSet<String>();
        names.forEach(name -> existing.add(packageName(name)));
        var moved = new HashMap<String, String>();
        for (var mapping : aliases.entrySet()) {
            String from = packageName(mapping.getKey());
            String to = packageName(mapping.getValue());
            if (from.equals(to)) continue;
            String previous = moved.putIfAbsent(from, to);
            if (existing.contains(to) || previous != null && !previous.equals(to))
                throw new IOException("Package relocation requires one fresh destination for " + from);
        }
        if (new HashSet<>(moved.values()).size() != moved.size())
            throw new IOException("Class map cannot merge packages");
        for (String name : names) {
            String expected = moved.get(packageName(name));
            if (expected != null && !expected.equals(packageName(aliases.getOrDefault(name, name))))
                throw new IOException("Package relocation must map every class in the package, including " + name);
        }
        return moved.keySet();
    }

    private static void checkMetadata(ClassNode node, Map<String, String> aliases, SimpleRemapper remapper) {
        if (node.attrs != null || node.methods.stream().anyMatch(method -> method.attrs != null)
            || node.fields.stream().anyMatch(field -> field.attrs != null)
            || node.recordComponents != null && node.recordComponents.stream().anyMatch(component -> component.attrs != null))
            throw new IllegalArgumentException("Custom attributes may contain class references");
        if (hasAnnotation(node.visibleAnnotations, "Lkotlin/Metadata;")
            || hasAnnotation(node.invisibleAnnotations, "Lkotlin/Metadata;"))
            throw new IllegalArgumentException("Kotlin classes require a Kotlin metadata aware remapper");
        if (aliases.containsKey(node.name) && (hasAnnotation(node.visibleAnnotations, "Lorg/spongepowered/asm/mixin/Mixin;")
            || hasAnnotation(node.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Mixin;")))
            throw new IllegalArgumentException("Mixin class must retain its name");
        for (var method : node.methods) {
            if ((method.access & ACC_NATIVE) != 0 && (aliases.containsKey(node.name)
                || !method.desc.equals(remapper.mapMethodDesc(method.desc))))
                throw new IllegalArgumentException("Class map changes an existing JNI declaration: " + method.name);
        }
        for (var inner : node.innerClasses) checkEnclosing(inner.name, inner.outerName, aliases);
        checkEnclosing(node.name, node.outerClass, aliases);
    }

    private static void checkEnclosing(String name, String outer, Map<String, String> aliases) {
        if (outer == null || !aliases.containsKey(name) && !aliases.containsKey(outer)) return;
        String child = aliases.getOrDefault(name, name);
        String prefix = aliases.getOrDefault(outer, outer) + "$";
        if (!child.startsWith(prefix) || child.length() == prefix.length())
            throw new IllegalArgumentException("Nested class " + name + " must map under " + prefix);
    }

    private static boolean hasAnnotation(List<AnnotationNode> annotations, String descriptor) {
        return annotations != null && annotations.stream().anyMatch(annotation -> annotation.desc.equals(descriptor));
    }

    private static Set<String> externalNames(Map<String, byte[]> files) throws IOException {
        var keep = new HashSet<>(FabricExclusions.forClassMapping(files).keySet());
        byte[] data = files.get("META-INF/MANIFEST.MF");
        if (data != null) {
            var manifest = new Manifest(new ByteArrayInputStream(data));
            if (!manifest.getEntries().isEmpty() || "true".equalsIgnoreCase(manifest.getMainAttributes().getValue("Sealed")))
                throw new IOException("Class maps require a manifest without sealed packages or entry sections");
            for (String key : List.of("Main-Class", "Premain-Class", "Agent-Class", "Launcher-Agent-Class")) {
                String name = manifest.getMainAttributes().getValue(key);
                if (name != null) keep.add(name.strip().replace('.', '/'));
            }
        }
        for (var file : files.entrySet()) {
            if (!file.getKey().startsWith(SERVICES)) continue;
            keep.add(file.getKey().substring(SERVICES.length()).replace('.', '/'));
            new String(file.getValue(), StandardCharsets.UTF_8).lines().forEach(line -> {
                String provider = line.split("#", 2)[0].strip();
                if (!provider.isEmpty()) keep.add(provider.replace('.', '/'));
            });
        }
        return keep;
    }

    private static String packageName(String name) {
        return name.substring(0, name.lastIndexOf('/') + 1);
    }

    private static String simpleName(String name) {
        return name.substring(name.lastIndexOf('/') + 1);
    }
}
