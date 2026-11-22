import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import net.fabricmc.classtweaker.api.ClassTweaker;
import net.fabricmc.classtweaker.api.ClassTweakerReader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/** Builds a library overlay for ProGuard from the remapped mod's access widener. */
public final class ProGuardLibrary {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected REMAPPED_MOD INTERMEDIARY_SERVER OUTPUT");
        var tweaker = ClassTweaker.newInstance();
        try (var mod = new JarFile(args[0]);
             var rules = mod.getInputStream(mod.getJarEntry("native-fixture.accesswidener"))) {
            ClassTweakerReader.create(tweaker).read(rules.readAllBytes(), "intermediary");
        }
        try (var server = new JarFile(args[1]);
             var overlay = new JarOutputStream(Files.newOutputStream(Path.of(args[2])))) {
            for (String target : tweaker.getTargets()) {
                String name = target.replace('.', '/') + ".class";
                JarEntry entry = server.getJarEntry(name);
                if (entry == null) throw new IllegalArgumentException("Missing access-widener target " + name);
                try (var original = server.getInputStream(entry)) {
                    var reader = new ClassReader(original);
                    var writer = new ClassWriter(reader, 0);
                    reader.accept(tweaker.createClassVisitor(Opcodes.ASM9, writer, (unused, bytecode) -> {
                        throw new IllegalArgumentException("Unexpected generated class " + unused);
                    }), 0);
                    overlay.putNextEntry(new JarEntry(name));
                    overlay.write(writer.toByteArray());
                    overlay.closeEntry();
                }
            }
        }
    }
}
