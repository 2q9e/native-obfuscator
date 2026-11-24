package dev.nativeobfuscator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import dev.nativeobfuscator.AnalysisNative.EdgeEvidence;
import dev.nativeobfuscator.AnalysisNative.Evidence;
import dev.nativeobfuscator.AnalysisNative.Selection;

/** Checks direct control-flow edges in the exact stripped image supplied by the native link. */
final class AnalysisNativeEvidence {
    private static final int MAX_DISASSEMBLY = 4 * 1024 * 1024;
    private static final int MAX_ROOT_BYTES = 64 * 1024;
    private static final int MAX_HELPER_BYTES = 1024;
    private static final Pattern SECTION = Pattern.compile(
        "^\\s*\\d+\\s+(\\.\\w+)\\s+([\\da-fA-F]+)\\s+([\\da-fA-F]+)\\s+TEXT(?:\\s.*)?$");
    private static final String DISASSEMBLY = "Disassembly of section ";
    private static final Pattern INSTRUCTION = Pattern.compile(
        "^\\s*([\\da-fA-F]+):\\s+([\\da-fA-F]{8}\\s+|(?:[\\da-fA-F]{2}\\s+)+)(\\S+)(?:\\s+(.*))?$");
    private static final Pattern ADDRESS = Pattern.compile("(?:^|,)\\s*0x([\\da-fA-F]+)(?=\\s|$)");

    private AnalysisNativeEvidence() {}

    private record Section(long address, long bytes, Map<Long, Instruction> instructions) {
        private Section(long address, long bytes) {
            this(address, bytes, new HashMap<>());
        }
    }

    private record Instruction(long address, int bytes, String mnemonic, Long target) {}

    private enum Flow { NEXT, CALL, TAIL_BRANCH, CONDITIONAL, STOP }

    static String objdump(String clang) {
        String configuredObjdump = System.getenv("LLVM_OBJDUMP");
        if (configuredObjdump != null && !configuredObjdump.isBlank()) return configuredObjdump;
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
        String name = "llvm-objdump" + (windows ? ".exe" : "");
        if (clang != null) {
            Path configured = Path.of(clang).toAbsolutePath();
            Path sibling = configured.resolveSibling(name);
            if (Files.isRegularFile(sibling)) return sibling.toString();
        }
        return name;
    }

    static List<Evidence> verify(Path library, NativeTarget target, String objdump, List<Selection> selected)
            throws InterruptedException {
        if (selected.isEmpty()) return List.of();
        if (objdump == null) return unavailable(selected, "linked disassembly tool was not configured");
        boolean arm = target == NativeTarget.LINUX_ARM64 || target == NativeTarget.WINDOWS_ARM64;
        String format = switch (target) {
            case LINUX_X64 -> "elf64-x86-64";
            case LINUX_ARM64 -> "elf64-littleaarch64";
            case WINDOWS_X64 -> "coff-x86-64";
            case WINDOWS_ARM64 -> "coff-arm64";
        };

        Map<String, Section> sections;
        try {
            String disassembled = disassemble(library, objdump, selected);
            if (disassembled.lines().noneMatch(line -> line.stripTrailing().endsWith("file format " + format)))
                return unavailable(selected, "linked disassembly did not identify the expected " + format + " image");
            sections = parse(disassembled);
        } catch (IOException | NumberFormatException problem) {
            return unavailable(selected, problem.getMessage());
        }

        var result = new ArrayList<Evidence>(selected.size());
        for (Selection site : selected) {
            var edges = new ArrayList<EdgeEvidence>(site.helperCount());
            String source = site.rootSection();
            for (var helper : site.helpers()) {
                edges.add(checkEdge(source, helper.section(), sections, edges.isEmpty(), arm));
                source = helper.section();
            }
            EdgeEvidence first = edges.getFirst();
            EdgeEvidence failed = edges.stream().filter(edge -> !edge.status().equals("verified")).findFirst().orElse(null);
            String status = failed == null ? "verified" : "unverified";
            String reason;
            if (edges.size() == 1) {
                reason = first.reason();
            } else if (failed == null) {
                reason = "All " + edges.size() + " direct helper edges are statically reachable from their section entries";
            } else {
                reason = failed.sourceSection() + " to " + failed.targetSection() + " failed because " + failed.reason();
            }
            result.add(new Evidence(site.root(), status, reason, first.sourceBytes(), first.targetBytes(),
                first.edgeOffset(), edges));
        }
        return List.copyOf(result);
    }

    private static EdgeEvidence checkEdge(String sourceName, String targetName, Map<String, Section> sections,
                                          boolean root, boolean arm) {
        Section source = sections.get(sourceName);
        Section target = sections.get(targetName);
        Long sourceBytes = source == null ? null : source.bytes();
        Long targetBytes = target == null ? null : target.bytes();
        String sourceRole = root ? "root" : "source helper";
        if (source == null || target == null) {
            String absent = source == null && target == null ? sourceRole + " and destination helper"
                : source == null ? sourceRole : "destination helper";
            return new EdgeEvidence(sourceName, targetName, "unverified", absent + " executable section missing",
                sourceBytes, targetBytes, null);
        }
        if (source.bytes() > (root ? MAX_ROOT_BYTES : MAX_HELPER_BYTES) || target.bytes() > MAX_HELPER_BYTES)
            return new EdgeEvidence(sourceName, targetName, "unverified", "native helper or root exceeds its section size limit",
                sourceBytes, targetBytes, null);
        if (!source.instructions().containsKey(source.address()) || !target.instructions().containsKey(target.address()))
            return new EdgeEvidence(sourceName, targetName, "unverified", "source or helper entry could not be disassembled",
                sourceBytes, targetBytes, null);
        Instruction transfer = reachableTransfer(source, target.address(), arm);
        if (transfer == null)
            return new EdgeEvidence(sourceName, targetName, "unverified",
                "no direct transfer to the helper entry was found from the " + sourceRole + " entry",
                sourceBytes, targetBytes, null);
        String transferType = flow(transfer.mnemonic(), arm) == Flow.CALL ? "call" : "tail branch";
        return new EdgeEvidence(sourceName, targetName, "verified",
            "direct " + transferType + " to the helper is statically reachable from the " + sourceRole + " entry",
            sourceBytes, targetBytes, transfer.address() - source.address());
    }

    static List<Evidence> unavailable(List<Selection> selected, String reason) {
        var result = new ArrayList<Evidence>(selected.size());
        for (Selection site : selected) {
            var edges = new ArrayList<EdgeEvidence>(site.helperCount());
            String source = site.rootSection();
            for (var helper : site.helpers()) {
                edges.add(new EdgeEvidence(source, helper.section(), "unavailable", reason, null, null, null));
                source = helper.section();
            }
            result.add(new Evidence(site.root(), "unavailable", reason, null, null, null, edges));
        }
        return List.copyOf(result);
    }

    private static String disassemble(Path library, String objdump, List<Selection> selected)
            throws IOException, InterruptedException {
        var command = new ArrayList<>(List.of(objdump, "-h", "-d"));
        for (Selection site : selected) {
            command.add("--section=" + site.rootSection());
            for (var helper : site.helpers()) command.add("--section=" + helper.section());
        }
        Path image = library.toAbsolutePath();
        command.add(image.toString());
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("LC_ALL", "C");
        Process process;
        try {
            process = builder.start();
        } catch (IOException failure) {
            throw new IOException("could not start linked disassembly tool " + objdump, failure);
        }
        var output = new CompletableFuture<byte[]>();
        try {
            Thread.startVirtualThread(() -> {
                try (var input = process.getInputStream()) {
                    byte[] bytes = input.readNBytes(MAX_DISASSEMBLY + 1);
                    if (bytes.length > MAX_DISASSEMBLY)
                        throw new IOException("linked disassembly exceeds the analysis size limit");
                    output.complete(bytes);
                } catch (IOException failure) {
                    output.completeExceptionally(failure);
                }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            byte[] bytes = output.get(30, TimeUnit.SECONDS);
            if (!process.waitFor(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS))
                throw new IOException("linked disassembly timed out");
            String disassembly = new String(bytes, StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                String detail = disassembly.lines().filter(line -> !line.isBlank()).findFirst().orElse("").strip();
                if (detail.length() > 320) detail = detail.substring(0, 320);
                throw new IOException("linked disassembly exited with status " + process.exitValue()
                    + (detail.isEmpty() ? "" : ": " + detail));
            }
            return disassembly;
        } catch (TimeoutException failure) {
            throw new IOException("linked disassembly timed out", failure);
        } catch (ExecutionException failure) {
            throw new IOException(failure.getCause().getMessage(), failure.getCause());
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }

    private static Map<String, Section> parse(String disassembled) throws IOException {
        Map<String, Section> sections = new HashMap<>();
        Section active = null;
        for (String line : disassembled.split("\\R")) {
            var entry = SECTION.matcher(line);
            if (entry.matches()) {
                long address = hex(entry.group(3));
                long bytes = hex(entry.group(2));
                if (bytes <= 0) continue;
                if (sections.putIfAbsent(entry.group(1), new Section(address, bytes)) != null)
                    throw new IOException("Duplicate executable section in linked disassembly: " + entry.group(1));
                continue;
            }
            if (line.startsWith(DISASSEMBLY) && line.endsWith(":")) {
                active = sections.get(line.substring(DISASSEMBLY.length(), line.length() - 1));
                continue;
            }
            if (active == null) continue;
            var decoded = INSTRUCTION.matcher(line);
            if (!decoded.matches()) continue;
            long address = hex(decoded.group(1));
            int bytes = decoded.group(2).replaceAll("\\s+", "").length() / 2;
            long offset = address - active.address();
            if (offset < 0 || offset >= active.bytes() || bytes > active.bytes() - offset) continue;
            String mnemonic = decoded.group(3);
            Long target = null;
            if (decoded.group(4) != null) {
                var operand = ADDRESS.matcher(decoded.group(4));
                if (operand.find()) target = hex(operand.group(1));
            }
            if (active.instructions().putIfAbsent(address, new Instruction(address, bytes, mnemonic, target)) != null)
                throw new IOException("Duplicate instruction address in linked disassembly");
        }
        return sections;
    }

    private static Instruction reachableTransfer(Section root, long helper, boolean arm) {
        var pending = new ArrayDeque<Long>();
        var seen = new HashSet<Long>();
        pending.add(root.address());
        while (!pending.isEmpty()) {
            long address = pending.removeFirst();
            if (!seen.add(address)) continue;
            Instruction instruction = root.instructions().get(address);
            if (instruction == null) continue;
            Flow flow = flow(instruction.mnemonic(), arm);
            Long target = instruction.target();
            if ((flow == Flow.CALL || flow == Flow.TAIL_BRANCH) && target != null && target == helper)
                return instruction;
            if ((flow == Flow.TAIL_BRANCH || flow == Flow.CONDITIONAL) && target != null)
                pending.add(target);
            // A call can return to the following instruction, including indirect JNI calls.
            if (flow == Flow.NEXT || flow == Flow.CONDITIONAL || flow == Flow.CALL)
                pending.add(address + instruction.bytes());
        }
        return null;
    }

    private static Flow flow(String mnemonic, boolean arm) {
        if (mnemonic.startsWith(".") || mnemonic.startsWith("<")) return Flow.STOP;
        if (arm) {
            if (mnemonic.startsWith("b.") || mnemonic.startsWith("bc.")) return Flow.CONDITIONAL;
            return switch (mnemonic) {
                case "bl", "blr", "blraa", "blraaz", "blrab", "blrabz" -> Flow.CALL;
                case "b" -> Flow.TAIL_BRANCH;
                case "cbz", "cbnz", "tbz", "tbnz" -> Flow.CONDITIONAL;
                case "br", "braa", "braaz", "brab", "brabz",
                     "ret", "retaa", "retab", "eret", "eretaa", "eretab", "drps",
                     "brk", "udf", "hlt", "svc", "hvc", "smc", "dcps1", "dcps2", "dcps3" -> Flow.STOP;
                default -> Flow.NEXT;
            };
        }
        return switch (mnemonic) {
            case "call", "callq" -> Flow.CALL;
            case "jmp", "jmpq" -> Flow.TAIL_BRANCH;
            case "loop", "loope", "loopne", "loopz", "loopnz" -> Flow.CONDITIONAL;
            case "ret", "retq", "retl", "lret", "lretq", "lretl", "iret", "iretq", "iretl",
                 "ljmp", "ljmpq", "lcall", "lcallq", "int", "int1", "int3", "into",
                 "ud0", "ud1", "ud2", "hlt", "syscall", "sysenter", "sysexit", "sysret",
                 "rep", "repe", "repne", "repz", "repnz", "bnd", "notrack" -> Flow.STOP;
            default -> mnemonic.startsWith("j") ? Flow.CONDITIONAL : Flow.NEXT;
        };
    }

    private static long hex(String value) {
        return Long.parseUnsignedLong(value, 16);
    }
}
