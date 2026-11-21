package dev.nativeobfuscator;

import dev.nativeobfuscator.translate.MethodCompiler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;

import static org.objectweb.asm.Opcodes.*;

public final class AnalysisNativeTest {
    private static final long SEED = 0x40c9_b298_23ba_5dc1L;
    private static final String INT_ROOT = "method_test_subtract";
    private static final String LONG_ROOT = "method_test_multiply";

    public static void main(String[] args) throws Exception {
        MethodNode narrow = subtraction();
        MethodNode wide = multiplication();
        checkSelection(narrow, wide);
        if (args.length != 0) {
            require(args.length == 3, "usage: AnalysisNativeTest [zig llvm-objdump scratch-directory]");
            checkLinked(Path.of(args[2]), args[0], args[1], narrow, wide);
        }
        System.out.println("PASS analysis native helper");
    }

    private static void checkSelection(MethodNode narrow, MethodNode wide) throws Exception {
        var analysis = new AnalysisNative(SEED, 1);
        var site = analysis.prepare(narrow, INT_ROOT, narrow.name + narrow.desc);
        require(site.enabled() && site.opcode() == ISUB, "returning subtraction was not eligible");
        require(site.equals(analysis.prepare(narrow, INT_ROOT, narrow.name + narrow.desc)) && analysis.selectedCount() == 0,
            "preparing a site should be reproducible without consuming the budget");
        var renamed = analysis.prepare(narrow, "method_different_runtime", narrow.name + narrow.desc);
        require(renamed.rootSection().equals(site.rootSection()) && renamed.reversed() == site.reversed(),
            "runtime naming changed the per-method analysis seed stream");
        require(site.lower(site.instructionIndex() - 1, ISUB, "a", "b") == null, "nonselected instruction was lowered");
        require(site.lower(site.instructionIndex(), IADD, "a", "b") == null, "nonselected opcode was lowered");
        require(site.lower(site.instructionIndex(), ISUB, "a", "b").contains(site.helper()),
            "selected instruction was not lowered");
        require(site.support().contains("noinline") && site.support().contains("uint32_t"),
            "integer helper ABI was not emitted");
        boolean varied = false;
        for (long seed = 0; seed < 64; seed++) {
            var candidate = new AnalysisNative(seed, 1).prepare(narrow, INT_ROOT, narrow.name + narrow.desc);
            if (candidate.reversed() != site.reversed()) varied = true;
        }
        require(varied, "seed had no effect on the helper operand order");
        analysis.accept(site);
        require(analysis.selectedCount() == 1 && analysis.prepare(wide, LONG_ROOT, wide.name + wide.desc).reason().contains("limit"),
            "selected site did not consume the method budget");

        var skipped = new AnalysisNative(SEED, AnalysisNative.MAX_METHODS);
        var constant = method("constant", "()I");
        constant.visitInsn(ICONST_1);
        constant.visitInsn(ICONST_2);
        constant.visitInsn(IADD);
        constant.visitInsn(IRETURN);
        constant.visitMaxs(2, 0);
        require(skipped.prepare(constant, "method_constant", constant.name + constant.desc).reason().contains("does not read an argument"),
            "constant-only operation was eligible");

        var branch = method("branch", "(I)I");
        var continuation = new LabelNode();
        branch.visitVarInsn(ILOAD, 0);
        branch.visitJumpInsn(IFNE, continuation.getLabel());
        branch.visitInsn(ICONST_0);
        branch.visitInsn(IRETURN);
        branch.visitLabel(continuation.getLabel());
        branch.visitVarInsn(ILOAD, 0);
        branch.visitInsn(ICONST_1);
        branch.visitInsn(IADD);
        branch.visitInsn(IRETURN);
        branch.visitMaxs(2, 1);
        require(skipped.prepare(branch, "method_branch", branch.name + branch.desc).reason().contains("straight-line"),
            "multi-path method was eligible");

        String baseline = MethodCompiler.compile(owner(), narrow, INT_ROOT);
        var disabledSite = skipped.prepare(constant, "method_constant", constant.name + constant.desc);
        String disabled = MethodCompiler.compile(owner(), narrow, INT_ROOT, "", disabledSite::lower);
        require(baseline.equals(disabled), "disabled helper changed emitted C");
        String lowered = MethodCompiler.compile(owner(), narrow, INT_ROOT, site.rootAttribute(), site::lower);
        require(lowered.contains(site.rootSection()) && lowered.contains("bits_i(" + site.helper()),
            "method compiler did not consume the selected C hook");
    }

    private static MethodNode subtraction() {
        var method = method("subtract", "(II)I");
        method.visitVarInsn(ILOAD, 0);
        method.visitVarInsn(ILOAD, 1);
        method.visitInsn(ISUB);
        method.visitInsn(IRETURN);
        method.visitMaxs(2, 2);
        return method;
    }

    private static MethodNode multiplication() {
        var method = method("multiply", "(JJ)J");
        method.visitVarInsn(LLOAD, 0);
        method.visitInsn(LNEG);
        method.visitVarInsn(LLOAD, 2);
        method.visitInsn(LMUL);
        method.visitInsn(LRETURN);
        method.visitMaxs(4, 4);
        return method;
    }

    private static MethodNode method(String name, String descriptor) {
        return new MethodNode(ACC_PUBLIC | ACC_STATIC, name, descriptor, null, null);
    }

    private static ClassNode owner() {
        var owner = new ClassNode();
        owner.name = "AnalysisNativeFixture";
        return owner;
    }

    private static void checkLinked(Path scratch, String zig, String objdump, MethodNode narrow, MethodNode wide)
            throws Exception {
        Files.createDirectories(scratch);
        Path jni = Path.of(System.getProperty("java.home"), "include");
        Files.writeString(scratch.resolve("jni_md.h"), """
            #if defined(_WIN32)
            #define JNIEXPORT __declspec(dllexport)
            #else
            #define JNIEXPORT __attribute__((visibility("default")))
            #endif
            #define JNIIMPORT
            #define JNICALL
            typedef int jint;
            typedef long long jlong;
            typedef signed char jbyte;
            """);

        var analysis = new AnalysisNative(SEED, 2);
        var integer = analysis.prepare(narrow, INT_ROOT, narrow.name + narrow.desc);
        analysis.accept(integer);
        var longInteger = analysis.prepare(wide, LONG_ROOT, wide.name + wide.desc);
        analysis.accept(longInteger);
        require(longInteger.enabled() && longInteger.support().contains("uint64_t"), "long helper ABI was not emitted");
        String support = MethodCompiler.runtimeSupport() + integer.support() + longInteger.support();
        String intSource = MethodCompiler.compile(owner(), narrow, INT_ROOT, integer.rootAttribute(), integer::lower);
        String longSource = MethodCompiler.compile(owner(), wide, LONG_ROOT, longInteger.rootAttribute(), longInteger::lower);
        String exports = """
            JNIEXPORT void *integer_root(void) { return (void *)method_test_subtract; }
            JNIEXPORT void *long_root(void) { return (void *)method_test_multiply; }
            """;
        Path source = Files.writeString(scratch.resolve("native-helper.c"), support + intSource + longSource + exports);
        for (NativeTarget target : NativeTarget.values()) {
            Path lib = scratch.resolve(target.key + "-" + target.file);
            link(zig, target, jni, scratch, source, lib, true);
            var evidence = analysis.verify(lib, target, objdump);
            require(evidence.size() == 2 && evidence.stream().allMatch(result ->
                result.status().equals("verified") && result.rootBytes() > 0 && result.helperBytes() > 0
                    && result.edgeOffset() >= 0 && result.edgeOffset() < result.rootBytes()), target.key + " " + evidence);
            require(analysis.verify(lib, target, null).stream().allMatch(result -> result.status().equals("unavailable")),
                "missing disassembler was not reported for " + target.key);
        }

        NativeTarget host = NativeTarget.host();
        String unrelatedIntSource = MethodCompiler.compile(owner(), narrow, INT_ROOT, integer.rootAttribute(), null);
        Path unrelated = Files.writeString(scratch.resolve("unrelated-helper.c"),
            support + unrelatedIntSource + longSource + exports
                + "JNIEXPORT void *unrelated_address(void) { return (void *)" + integer.helper() + "; }\n");
        Path unrelatedLib = scratch.resolve("unrelated-" + host.file);
        link(zig, host, jni, scratch, unrelated, unrelatedLib, true);
        var unrelatedEvidence = analysis.verify(unrelatedLib, host, objdump);
        require(unrelatedEvidence.getFirst().status().equals("unverified") && unrelatedEvidence.getFirst().helperBytes() > 0
                && unrelatedEvidence.getFirst().edgeOffset() == null && unrelatedEvidence.getLast().status().equals("verified"),
            "present but unrelated helper incorrectly verified: " + unrelatedEvidence);

        Path executableSource = Files.writeString(scratch.resolve("native-helper-main.c"), support + intSource + longSource + """
            int main(void) {
                if ((uint32_t)method_test_subtract(0, 0, INT32_MIN, 1) != UINT32_C(0x7fffffff)) return 1;
                if ((uint32_t)method_test_subtract(0, 0, 5, 12) != UINT32_C(0xfffffff9)) return 2;
                if ((uint64_t)method_test_multiply(0, 0, INT64_MIN, 3) != UINT64_C(0x8000000000000000)) return 3;
                if ((uint64_t)method_test_multiply(0, 0, -7, 17) != 119) return 4;
                return 0;
            }
            """);
        Path executable = scratch.resolve(host.key.startsWith("windows") ? "native-helper.exe" : "native-helper-run");
        link(zig, host, jni, scratch, executableSource, executable, false);
        execute(List.of(executable.toAbsolutePath().toString()));
    }

    private static void link(String zig, NativeTarget target, Path jniInclude, Path scratch, Path source,
                             Path output, boolean shared) throws Exception {
        var command = new java.util.ArrayList<>(List.of(zig, "cc", "-target", target.triple, "-O2",
            "-I", jniInclude.toString(), "-I", scratch.toString(), "-fvisibility=hidden", "-s"));
        if (shared) command.add("-shared");
        command.addAll(List.of(source.toString(), "-o", output.toString()));
        execute(command);
    }

    private static void execute(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).inheritIO().start();
        try {
            require(process.waitFor(120, TimeUnit.SECONDS), "command timed out: " + command);
            require(process.exitValue() == 0, "command failed: " + command);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static void require(boolean expected, String detail) {
        if (!expected) throw new AssertionError(detail);
    }
}
