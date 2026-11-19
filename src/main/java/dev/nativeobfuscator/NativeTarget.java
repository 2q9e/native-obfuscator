package dev.nativeobfuscator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

enum NativeTarget {
    LINUX_X64("x86_64-linux", "x86_64-linux-gnu.2.28", "libprotected.so"),
    LINUX_ARM64("aarch64-linux", "aarch64-linux-gnu.2.28", "libprotected.so"),
    WINDOWS_X64("x86_64-windows", "x86_64-windows-gnu", "protected.dll"),
    WINDOWS_ARM64("aarch64-windows", "aarch64-windows-gnu", "protected.dll");

    final String key;
    final String triple;
    final String file;

    NativeTarget(String key, String triple, String file) {
        this.key = key;
        this.triple = triple;
        this.file = file;
    }

    static NativeTarget parse(String key) {
        for (var target : values()) {
            if (target.key.equals(key)) return target;
        }
        throw new IllegalArgumentException("Unknown target " + key);
    }

    static NativeTarget host() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = switch (System.getProperty("os.arch").toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64" -> "x86_64";
            case "arm64", "aarch64" -> "aarch64";
            default -> throw new IllegalArgumentException("Unsupported host architecture, specify --target");
        };
        if (os.equals("linux")) return parse(arch + "-linux");
        if (os.startsWith("windows")) return parse(arch + "-windows");
        throw new IllegalArgumentException("Unsupported host OS, specify --target");
    }

    Path compile(Path workspace, Path source, Protector.Config config) throws IOException, InterruptedException {
        Path output = Files.createDirectory(workspace.resolve(key)).resolve(file);
        Path headers = Path.of(System.getProperty("java.home"), "include");
        if (!Files.isRegularFile(headers.resolve("jni.h")))
            throw new IOException("Run the tool with a JDK containing include/jni.h");
        Path platformHeaders = output.getParent().resolve("jni_md.h");
        // Bind only the platform-specific typedefs and macros expected by the build JDK's JNI header.
        Files.writeString(platformHeaders, """
            #ifndef NATIVE_OBFUSCATOR_JNI_MD_H
            #define NATIVE_OBFUSCATOR_JNI_MD_H
            #if defined(_WIN32)
            #define JNIEXPORT __declspec(dllexport)
            #define JNIIMPORT __declspec(dllimport)
            #define JNICALL
            #else
            #define JNIEXPORT __attribute__((visibility("default")))
            #define JNIIMPORT
            #define JNICALL
            #endif
            typedef int jint;
            typedef long long jlong;
            typedef signed char jbyte;
            #endif
            """);
        var flags = List.of("-std=c11", "-O2", "-ffp-contract=off", "-fno-fast-math",
            "-fvisibility=hidden", "-I", headers.toString(), "-I", output.getParent().toString());
        String machine = key.substring(0, key.indexOf('-'));
        boolean linux = key.endsWith("-linux");
        String clangTriple = machine + (linux ? "-unknown-linux-gnu" : "-w64-windows-gnu");
        if (config.llvmPlugin() != null) {
            var frontend = new ArrayList<String>();
            frontend.add(config.clang());
            frontend.addAll(flags.subList(0, 5));
            Path input = source;
            if (config.zig() != null) {
                input = output.resolveSibling("translated.i");
                var preprocessor = compiler(config);
                preprocessor.addAll(flags);
                preprocessor.addAll(List.of("-E", source.toString(), "-o", input.toString()));
                execute(preprocessor, "Zig preprocessing for " + key);
                frontend.addAll(List.of("-target", clangTriple));
            } else {
                if (this != host() || !linux)
                    throw new IOException("Use --zig PATH for LLVM target " + key);
                frontend.addAll(flags.subList(5, flags.size()));
            }
            Path optimized = output.resolveSibling("optimized.bc");
            Path transformed = output.resolveSibling("transformed.bc");
            if (linux) frontend.add("-fPIC");
            frontend.addAll(List.of("-emit-llvm", "-c", input.toString(), "-o", optimized.toString()));
            execute(frontend, "LLVM frontend for " + key);
            execute(List.of(config.llvmOpt(), "-load-pass-plugin=" + config.llvmPlugin(),
                "-passes=" + config.llvmPasses() + ",verify", optimized.toString(), "-o", transformed.toString()),
                "LLVM protection for " + key);
            lower(config, transformed, output, clangTriple, linux);
            if (config.maxGrowth() >= 0) {
                Path baseline = output.resolveSibling("baseline-" + file);
                lower(config, optimized, baseline, clangTriple, linux);
                long baselineBytes = Files.size(baseline);
                long protectedBytes = Files.size(output);
                System.err.printf(Locale.ROOT, "%s baseline=%d protected=%d bytes%n", key, baselineBytes, protectedBytes);
                if (protectedBytes * 100 > baselineBytes * (100L + config.maxGrowth()))
                    throw new IOException("Native code growth limit exceeded for " + key);
            }
        } else {
            var command = compiler(config);
            command.addAll(flags);
            if (linux) command.add("-fPIC");
            command.addAll(List.of("-shared", source.toString(), "-o", output.toString()));
            if (linux) command.add("-lm");
            execute(command, "Native compilation for " + key);
        }
        if (!Files.isRegularFile(output))
            throw new IOException("Compiler did not write the library for " + key);
        long size = Files.size(output);
        if (size == 0 || size > 128L * 1024 * 1024)
            throw new IOException("Invalid native library size for " + key + ": " + size);
        return output;
    }

    private ArrayList<String> compiler(Protector.Config config) throws IOException {
        if (config.zig() != null) return new ArrayList<>(List.of(config.zig(), "cc", "-target", triple));
        if (this != host() || !key.endsWith("-linux")) throw new IOException("Use --zig PATH for target " + key);
        String executable = config.clang() == null ? System.getenv().getOrDefault("CC", "cc") : config.clang();
        return new ArrayList<>(List.of(executable));
    }

    private void lower(Protector.Config config, Path bitcode, Path output, String clangTriple, boolean linux)
            throws IOException, InterruptedException {
        Path object = output.resolveSibling(output.getFileName() + ".o");
        var backend = new ArrayList<>(List.of(config.clang(), "-O0"));
        if (config.zig() != null) backend.addAll(List.of("-target", clangTriple));
        if (linux) backend.add("-fPIC");
        backend.addAll(List.of("-c", bitcode.toString(), "-o", object.toString()));
        execute(backend, "LLVM code generation for " + key);

        var command = compiler(config);
        command.addAll(List.of("-shared", object.toString(), "-o", output.toString()));
        if (linux) command.add("-lm");
        execute(command, "Native link for " + key);
    }

    private static void execute(List<String> command, String stage) throws IOException, InterruptedException {
        Process compiler = new ProcessBuilder(command).inheritIO().start();
        try {
            if (!compiler.waitFor(180, TimeUnit.SECONDS))
                throw new IOException(stage + " timed out");
            if (compiler.exitValue() != 0)
                throw new IOException(stage + " failed (exit " + compiler.exitValue() + ")");
        } finally {
            if (compiler.isAlive()) {
                compiler.descendants().forEach(ProcessHandle::destroyForcibly);
                compiler.destroyForcibly();
            }
        }
    }
}
