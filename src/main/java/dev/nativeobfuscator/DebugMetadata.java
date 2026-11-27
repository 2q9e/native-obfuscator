package dev.nativeobfuscator;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

final class DebugMetadata {
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";

    private DebugMetadata() {}

    static Map<String, byte[]> strip(Map<String, byte[]> files) throws IOException {
        var fabric = FabricExclusions.from(files);
        var result = new LinkedHashMap<String, byte[]>();
        for (var file : files.entrySet()) {
            String name = file.getKey();
            if (name.equals("previous-compilation-data.bin")) continue;
            byte[] bytes = file.getValue();
            if (name.endsWith(".class")) {
                try {
                    bytes = stripClass(bytes, fabric);
                } catch (RuntimeException failure) {
                    throw new IOException("Invalid class metadata in " + name, failure);
                }
            }
            result.put(name, bytes);
        }
        return result;
    }

    private static byte[] stripClass(byte[] bytes, Map<String, String> fabric) throws IOException {
        var reader = new ClassReader(bytes);
        var node = new ClassNode();
        reader.accept(node, 0);
        boolean mixin = fabric.getOrDefault(node.name, "").startsWith("Mixin")
            || hasMixinAnnotation(node.visibleAnnotations) || hasMixinAnnotation(node.invisibleAnnotations);

        // For opaque attributes, keep the original constant pool and bytecode offsets.
        // Only the surrounding debug tables can be removed safely in that case.
        if (node.attrs != null || node.fields.stream().anyMatch(field -> field.attrs != null)
            || node.methods.stream().anyMatch(method -> method.attrs != null)
            || node.recordComponents != null && node.recordComponents.stream().anyMatch(component -> component.attrs != null)) {
            return new AttributeFilter(reader, bytes, mixin).strip();
        }

        node.sourceFile = null;
        node.sourceDebug = null;
        for (var method : node.methods) {
            if (!mixin) method.localVariables = null;
            for (var instruction : method.instructions.toArray()) {
                if (instruction.getType() == AbstractInsnNode.LINE)
                    method.instructions.remove(instruction);
            }
        }
        var writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static boolean hasMixinAnnotation(List<AnnotationNode> annotations) {
        return annotations != null && annotations.stream().anyMatch(annotation -> MIXIN.equals(annotation.desc));
    }

    private static final class AttributeFilter {
        private final ClassReader reader;
        private final byte[] bytes;
        private final boolean keepLocals;
        private final char[] buffer;
        private int cursor;

        AttributeFilter(ClassReader reader, byte[] bytes, boolean keepLocals) {
            this.reader = reader;
            this.bytes = bytes;
            this.keepLocals = keepLocals;
            buffer = new char[reader.getMaxStringLength()];
        }

        byte[] strip() throws IOException {
            var output = new ByteArrayOutputStream(bytes.length);
            try (var out = new DataOutputStream(output)) {
                cursor = reader.header + 6;
                cursor += 2 + 2 * reader.readUnsignedShort(cursor);
                out.write(bytes, 0, cursor);
                members(out, false);
                members(out, true);
                attributes(out, false, false);
                if (cursor != bytes.length) throw new IOException("Trailing bytes after class attributes");
            }
            return output.toByteArray();
        }

        private int count() {
            int count = reader.readUnsignedShort(cursor);
            cursor += 2;
            return count;
        }

        private void members(DataOutputStream out, boolean methods) throws IOException {
            int members = count();
            out.writeShort(members);
            for (int i = 0; i < members; i++) {
                out.write(bytes, cursor, 6);
                cursor += 6;
                attributes(out, methods, false);
            }
        }

        private void attributes(DataOutputStream out, boolean methods, boolean code) throws IOException {
            int total = count();
            int retained = 0;
            var filtered = new ByteArrayOutputStream();
            try (var body = new DataOutputStream(filtered)) {
                for (int i = 0; i < total; i++) {
                    int start = cursor;
                    String name = reader.readUTF8(start, buffer);
                    int size = reader.readInt(start + 2);
                    int end = Math.addExact(start + 6, size);
                    if (size < 0 || end > bytes.length) throw new IOException("Invalid attribute length");
                    cursor = start + 6;
                    boolean drop = code
                        ? name.equals("LineNumberTable") || !keepLocals
                            && (name.equals("LocalVariableTable") || name.equals("LocalVariableTypeTable"))
                        : name.equals("SourceFile") || name.equals("SourceDebugExtension");
                    if (!drop) {
                        retained++;
                        if (methods && name.equals("Code")) {
                            byte[] replacement = code(end);
                            body.writeShort(reader.readUnsignedShort(start));
                            body.writeInt(replacement.length);
                            body.write(replacement);
                        } else {
                            body.write(bytes, start, 6 + size);
                        }
                    }
                    cursor = end;
                }
            }
            out.writeShort(retained);
            filtered.writeTo(out);
        }

        private byte[] code(int end) throws IOException {
            int start = cursor;
            cursor = Math.addExact(cursor + 8, reader.readInt(cursor + 4));
            cursor += 2 + 8 * reader.readUnsignedShort(cursor);
            var body = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(body)) {
                out.write(bytes, start, cursor - start);
                attributes(out, false, true);
                if (cursor != end) throw new IOException("Invalid Code attribute length");
            }
            return body.toByteArray();
        }
    }
}
