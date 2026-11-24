package dev.nativeobfuscator;

import dev.nativeobfuscator.translate.MethodCompiler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
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
        checkReturnSites();
        checkGraphPlans(narrow);
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
        require(AnalysisReturnSite.find(branch, 64) == null, "different return producers were eligible");

        String baseline = MethodCompiler.compile(owner(), narrow, INT_ROOT);
        var disabledSite = skipped.prepare(constant, "method_constant", constant.name + constant.desc);
        String disabled = MethodCompiler.compile(owner(), narrow, INT_ROOT, "", disabledSite::lower);
        require(baseline.equals(disabled), "disabled helper changed emitted C");
        String lowered = MethodCompiler.compile(owner(), narrow, INT_ROOT, site.rootAttribute(), site::lower);
        require(lowered.contains(site.rootSection()) && lowered.contains("bits_i(" + site.helper()),
            "method compiler did not consume the selected C hook");
    }

    private static void checkReturnSites() throws Exception {
        var balanced = new AnalysisNative(AnalysisConfig.resolve("balanced", "44", null, null));
        var maximum = new AnalysisNative(AnalysisConfig.resolve("maximum", "44", null, null));
        var branch = branchingAddition();
        String signature = branch.name + branch.desc;
        String root = "method_test_add";
        require(!balanced.prepare(branch, root, signature).enabled(), "balanced traced through a branch");
        var site = maximum.prepare(branch, root, signature);
        require(site.opcode() == IADD && site.helperCount() == 3 && site.equals(maximum.prepare(branch, root, signature)),
            "maximum did not reproducibly outline the joined arithmetic result");
        require(compile(branch, site).contains("bits_i(" + site.helper()), "joined arithmetic was not lowered");

        var call = method("call", "(II)I");
        call.visitVarInsn(ILOAD, 0);
        call.visitVarInsn(ILOAD, 1);
        call.visitInsn(IOR);
        call.visitMethodInsn(INVOKESTATIC, "Peer", "score", "(I)I", false);
        call.visitInsn(IRETURN);
        call.visitMaxs(2, 2);
        require(maximum.prepare(call, "method_call", call.name + call.desc).opcode() == IOR,
            "maximum skipped arithmetic feeding a returned call");

        var folded = method("folded", "(I)I");
        folded.visitInsn(ICONST_1);
        folded.visitInsn(ICONST_2);
        folded.visitInsn(IADD);
        folded.visitVarInsn(ISTORE, 1);
        folded.visitVarInsn(ILOAD, 1);
        folded.visitInsn(ICONST_2);
        folded.visitInsn(IXOR);
        folded.visitInsn(IRETURN);
        folded.visitMaxs(2, 2);
        require(!maximum.prepare(folded, "method_folded", folded.name + folded.desc).enabled(),
            "a constant expression became eligible through a local");
    }

    private static void checkGraphPlans(MethodNode method) {
        var analysis = new AnalysisNative(SEED, AnalysisNative.MAX_METHODS, AnalysisNative.MAX_HELPERS_PER_ROOT);
        var sectionIds = new HashSet<String>();
        for (int i = 0; i < AnalysisNative.MAX_METHODS; i++) {
            String root = INT_ROOT + i;
            String signature = method.name + method.desc + i;
            var site = analysis.prepare(method, root, signature);
            require(site.equals(analysis.prepare(method, root, signature)) && site.helperCount() == 3,
                "three-helper chain was not reproducible");
            require(sectionIds.add(site.rootSection().substring(4)) && site.rootSection().length() == 8,
                "duplicate or malformed root section ID");
            long covered = 0;
            for (var helper : site.helpers()) {
                require(sectionIds.add(helper.section().substring(4)) && helper.section().length() == 8,
                    "duplicate or malformed helper section ID");
                require(helper.mask() != 0 && (covered & helper.mask()) == 0, "input parts overlap or are empty");
                covered |= helper.mask();
            }
            require(covered == 0xffffffffL, "input parts omit bits");
            analysis.accept(site);
        }
        require(analysis.selectedCount() == 8 && analysis.helperCount() == 24, "maximum graph budget count");
        require(analysis.prepare(method, INT_ROOT, method.name + method.desc).reason().contains("method limit"),
            "graph exceeded the root limit");
        require(!new AnalysisNative(SEED, 8, 0).prepare(method, INT_ROOT, method.name + method.desc).enabled(),
            "zero helpers did not disable the graph");
        MethodNode zero = withConstant("zero", IAND, true);
        require(new AnalysisNative(SEED, 8, 3).prepare(zero, "method_zero", zero.name + zero.desc)
            .reason().contains("annihilating literal"), "a constant result was eligible for a helper graph");

        String signature = method.name + method.desc;
        var reference = new AnalysisNative(SEED, 1, 3).prepare(method, INT_ROOT, signature);
        var orders = new HashSet<List<Long>>();
        var layouts = new HashSet<String>();
        for (long seed = 0; seed < 64; seed++) {
            var site = new AnalysisNative(seed, 1, 3).prepare(method, INT_ROOT, signature);
            orders.add(site.helpers().stream().map(AnalysisNative.Helper::mask).toList());
            layouts.add(site.rootSection());
        }
        require(orders.size() > 1 && reference.helperCount() == 3, "seed had no effect on helper partitions");
        require(layouts.size() > 1, "seed had no effect on section IDs");
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

    private static MethodNode branchingAddition() {
        var method = method("add", "(II)I");
        var zero = new LabelNode().getLabel();
        var join = new LabelNode().getLabel();
        method.visitVarInsn(ILOAD, 0);
        method.visitJumpInsn(IFEQ, zero);
        method.visitVarInsn(ILOAD, 1);
        method.visitJumpInsn(GOTO, join);
        method.visitLabel(zero);
        method.visitVarInsn(ILOAD, 0);
        method.visitLabel(join);
        method.visitVarInsn(ILOAD, 1);
        method.visitInsn(IADD);
        method.visitVarInsn(ISTORE, 2);
        method.visitVarInsn(ILOAD, 2);
        method.visitInsn(IRETURN);
        method.visitMaxs(2, 3);
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

        var analysis = new AnalysisNative(AnalysisConfig.resolve("maximum", Long.toUnsignedString(SEED), null, null));
        var methods = new ArrayList<>(List.of(narrow, wide));
        methods.addAll(List.of(
            branchingAddition(), operation("or", LOR, true), operation("and", LAND, true),
            operation("xor", IXOR, false), withConstant("constant", IADD, false), withConstant("negate", ISUB, true)));
        var sites = new ArrayList<AnalysisNative.Selection>();
        var support = new StringBuilder(MethodCompiler.runtimeSupport());
        var roots = new StringBuilder();
        var exports = new StringBuilder();
        for (int i = 0; i < methods.size(); i++) {
            MethodNode method = methods.get(i);
            String root = "method_test_" + method.name;
            var site = analysis.prepare(method, root, method.name + method.desc);
            require(site.enabled(), "linked fixture was not eligible " + method.name);
            analysis.accept(site);
            sites.add(site);
            if (i != 0) roots.append(compile(method, site));
            exports.append("JNIEXPORT void *export_").append(method.name)
                .append("(void) { return (void *)").append(root).append("; }\n");
        }
        support.append(analysis.support());
        var integer = sites.getFirst();
        var longInteger = sites.get(1);
        require(longInteger.support().contains("uint64_t"), "long helper ABI was not emitted");
        support.append("""
            static __attribute__((noinline)) jint returning_call(jint value) {
                volatile jint result = value;
                return result;
            }
            static jint (*volatile returning_callback)(jint) = returning_call;
            """);
        String intSource = MethodCompiler.compile(owner(), narrow, INT_ROOT, integer.rootAttribute(),
            (index, opcode, left, right) -> integer.lower(index, opcode,
                "returning_callback(returning_call(" + left + "))", right));
        Path source = Files.writeString(scratch.resolve("native-helper.c"), support + intSource + roots + exports);
        for (NativeTarget target : NativeTarget.values()) {
            Path lib = scratch.resolve(target.key + "-" + target.file);
            link(zig, target, jni, scratch, source, lib, true);
            var evidence = analysis.verify(lib, target, objdump);
            require(evidence.size() == sites.size(), "evidence omitted roots on " + target.key);
            for (int i = 0; i < sites.size(); i++) checkEvidence(sites.get(i), evidence.get(i), target);
            require(analysis.verify(lib, target, null).stream().allMatch(result -> result.status().equals("unavailable")
                && result.edges().size() == 3 && result.edges().stream().allMatch(edge -> edge.status().equals("unavailable"))),
                "missing disassembler was not reported for " + target.key);
        }

        NativeTarget host = NativeTarget.host();
        String unrelatedIntSource = MethodCompiler.compile(owner(), narrow, INT_ROOT, integer.rootAttribute(), null);
        var exportedHelpers = new StringBuilder();
        for (int i = 0; i < integer.helperCount(); i++)
            exportedHelpers.append("JNIEXPORT void *export_helper_").append(i).append("(void) { return (void *)")
                .append(integer.helpers().get(i).name()).append("; }\n");
        Path unrelated = Files.writeString(scratch.resolve("unrelated-helper.c"),
            support + unrelatedIntSource + roots + exports + exportedHelpers);
        Path unrelatedLib = scratch.resolve("unrelated-" + host.file);
        link(zig, host, jni, scratch, unrelated, unrelatedLib, true);
        var unrelatedEvidence = analysis.verify(unrelatedLib, host, objdump);
        require(unrelatedEvidence.getFirst().status().equals("unverified") && unrelatedEvidence.getFirst().helperBytes() > 0
                && unrelatedEvidence.getFirst().edgeOffset() == null && unrelatedEvidence.getLast().status().equals("verified"),
            "present but unrelated helper incorrectly verified: " + unrelatedEvidence);

        String nextHelper = integer.helpers().get(1).name();
        String call = "uint32_t prior = " + nextHelper + "(a, b);";
        String unreachableTransfer = host == NativeTarget.LINUX_ARM64 || host == NativeTarget.WINDOWS_ARM64
            ? "b 1f\\n\\tbl " : "jmp 1f\\n\\tcall ";
        String brokenSupport = support.toString().replace(call,
            "__asm__ volatile(\"" + unreachableTransfer + nextHelper + "\\n1:\"); uint32_t prior = a - b;");
        require(!brokenSupport.contentEquals(support), "unreachable edge fixture did not replace a helper call");
        Path broken = Files.writeString(scratch.resolve("unreachable-helper.c"),
            brokenSupport + intSource + roots + exports + exportedHelpers);
        Path brokenLib = scratch.resolve("unreachable-" + host.file);
        link(zig, host, jni, scratch, broken, brokenLib, true);
        var brokenEvidence = analysis.verify(brokenLib, host, objdump).getFirst();
        require(brokenEvidence.status().equals("unverified") && brokenEvidence.edgeOffset() != null
                && brokenEvidence.edges().get(1).status().equals("unverified")
                && brokenEvidence.edges().get(1).targetBytes() > 0 && brokenEvidence.edges().get(1).edgeOffset() == null
                && brokenEvidence.edges().get(2).status().equals("verified"),
            "unreachable transfer within a helper incorrectly verified: " + brokenEvidence);

        String missingSupport = support.toString();
        for (int i = 0; i < integer.helperCount(); i++) missingSupport = missingSupport.replace(integer.definition(i), "");
        Path missing = Files.writeString(scratch.resolve("missing-helper.c"), missingSupport + unrelatedIntSource + roots + exports);
        Path missingLib = scratch.resolve("missing-" + host.file);
        link(zig, host, jni, scratch, missing, missingLib, true);
        var missingEvidence = analysis.verify(missingLib, host, objdump).getFirst();
        require(missingEvidence.status().equals("unverified") && missingEvidence.rootBytes() > 0
                && missingEvidence.edges().stream().allMatch(edge -> edge.targetBytes() == null && edge.edgeOffset() == null),
            "an absent helper section incorrectly verified: " + missingEvidence);

        String main = """
            int main(void) {
                if ((uint32_t)method_test_subtract(0, 0, INT32_MIN, 1) != UINT32_C(0x7fffffff)) return 1;
                if ((uint32_t)method_test_subtract(0, 0, 5, 12) != UINT32_C(0xfffffff9)) return 2;
                if ((uint64_t)method_test_multiply(0, 0, INT64_MIN, 3) != UINT64_C(0x8000000000000000)) return 3;
                if ((uint64_t)method_test_multiply(0, 0, -7, 17) != 119) return 4;
                if (method_test_constant(0, 0, INT32_MAX) != INT32_MIN) return 5;
                if (method_test_negate(0, 0, -7) != 7) return 6;
                if (method_test_add(0, 0, 0, 7) != 7) return 7;
                if (method_test_add(0, 0, 1, INT32_MIN) != 0) return 8;
                return 0;
            }
            """;
        runExecutable(zig, host, jni, scratch, "root-semantics", support + intSource + roots + main);
        checkSemantics(zig, host, jni, scratch);
    }

    private static String compile(MethodNode method, AnalysisNative.Selection site) throws Exception {
        return MethodCompiler.compile(owner(), method, site.root(), site.rootAttribute(), site::lower);
    }

    private static void checkEvidence(AnalysisNative.Selection site, AnalysisNative.Evidence evidence, NativeTarget target) {
        require(evidence.root().equals(site.root()) && evidence.status().equals("verified")
                && evidence.edges().size() == site.helperCount(), target.key + " " + evidence);
        String sourceSection = site.rootSection();
        Long sourceBytes = evidence.rootBytes();
        for (int i = 0; i < site.helperCount(); i++) {
            var edge = evidence.edges().get(i);
            require(edge.sourceSection().equals(sourceSection) && edge.targetSection().equals(site.helpers().get(i).section())
                    && edge.sourceBytes().equals(sourceBytes) && edge.targetBytes() > 0 && edge.status().equals("verified")
                    && edge.edgeOffset() >= 0 && edge.edgeOffset() < sourceBytes, target.key + " " + edge);
            sourceSection = edge.targetSection();
            sourceBytes = edge.targetBytes();
        }
        var first = evidence.edges().getFirst();
        require(evidence.helperBytes().equals(first.targetBytes()) && evidence.edgeOffset().equals(first.edgeOffset()),
            "first-edge compatibility fields differ " + evidence);
    }

    private static MethodNode operation(String name, int opcode, boolean wide) {
        var method = method(name, wide ? "(JJ)J" : "(II)I");
        method.visitVarInsn(wide ? LLOAD : ILOAD, 0);
        method.visitVarInsn(wide ? LLOAD : ILOAD, wide ? 2 : 1);
        method.visitInsn(opcode);
        method.visitInsn(wide ? LRETURN : IRETURN);
        method.visitMaxs(wide ? 4 : 2, wide ? 4 : 2);
        return method;
    }

    private static MethodNode withConstant(String name, int opcode, boolean constantFirst) {
        var method = method(name, "(I)I");
        if (constantFirst) method.visitInsn(ICONST_0);
        method.visitVarInsn(ILOAD, 0);
        if (!constantFirst) method.visitInsn(ICONST_1);
        method.visitInsn(opcode);
        method.visitInsn(IRETURN);
        method.visitMaxs(2, 1);
        return method;
    }

    private static void checkSemantics(String zig, NativeTarget host, Path jni, Path scratch) throws Exception {
        int[] narrow = { IADD, ISUB, IMUL, IAND, IOR, IXOR };
        int[] wide = { LADD, LSUB, LMUL, LAND, LOR, LXOR };
        String[] operators = { "+", "-", "*", "&", "|", "^" };
        String main = """
                return 0;
            }
            int main(void) {
                uint64_t values[] = { 0, 1, 2, UINT32_C(0x7fffffff), UINT32_C(0x80000000),
                    UINT64_C(0x7fffffffffffffff), UINT64_C(0x8000000000000000), UINT64_MAX };
                for (unsigned i = 0; i < sizeof(values) / sizeof(values[0]); ++i) {
                    for (unsigned j = 0; j < sizeof(values) / sizeof(values[0]); ++j) {
                        int result = compare(values[i], values[j]);
                        if (result != 0) return result;
                    }
                }
                uint64_t state = UINT64_C(123456789);
                for (unsigned i = 0; i < 4096; ++i) {
                    state = state * UINT64_C(6364136223846793005) + UINT64_C(1);
                    uint64_t a = state;
                    state = state * UINT64_C(6364136223846793005) + UINT64_C(1);
                    int result = compare(a, state);
                    if (result != 0) return result;
                }
                return 0;
            }
            """;
        for (int depth = 1; depth <= AnalysisNative.MAX_HELPERS_PER_ROOT; depth++) {
            for (boolean longType : List.of(false, true)) {
                var emitted = new StringBuilder(MethodCompiler.runtimeSupport());
                var comparisons = new StringBuilder("static int compare(uint64_t a, uint64_t b) {\n");
                var analysis = new AnalysisNative(SEED + depth, 8, depth);
                String type = longType ? "uint64_t" : "uint32_t";
                String values = longType ? "bits_j(a), bits_j(b)" : "bits_i(a), bits_i(b)";
                for (int i = 0; i < operators.length; i++) {
                    String name = "operation_" + i;
                    MethodNode node = operation(name, (longType ? wide : narrow)[i], longType);
                    var site = analysis.prepare(node, name, name + node.desc);
                    analysis.accept(site);
                    emitted.append(site.support()).append(compile(node, site));
                    comparisons.append("    if ((").append(type).append(")").append(name).append("(0, 0, ")
                        .append(values).append(") != ((").append(type).append(")a ").append(operators[i])
                        .append(" (").append(type).append(")b)) return ").append(i + 1).append(";\n");
                }
                runExecutable(zig, host, jni, scratch, "semantics-" + depth + "-" + type,
                    emitted.append(comparisons).append(main).toString());
            }
        }
    }

    private static void runExecutable(String zig, NativeTarget host, Path jni, Path scratch, String name, String code)
            throws Exception {
        Path source = Files.writeString(scratch.resolve(name + ".c"), code);
        Path executable = scratch.resolve(name + (host.key.endsWith("-windows") ? ".exe" : ""));
        link(zig, host, jni, scratch, source, executable, false);
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
