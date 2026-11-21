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
            Section root = sections.get(site.rootSection());
            Section helper = sections.get(site.helperSection());
            Long rootBytes = root == null ? null : root.bytes();
            Long helperBytes = helper == null ? null : helper.bytes();
            if (root == null || helper == null) {
                String absent = root == null && helper == null ? "root and helper" : root == null ? "root" : "helper";
                result.add(new Evidence(site.root(), "unverified", absent + " executable section missing",
                    rootBytes, helperBytes, null));
                continue;
            }
            if (root.bytes() > MAX_ROOT_BYTES || helper.bytes() > MAX_HELPER_BYTES) {
                result.add(new Evidence(site.root(), "unverified", "native helper or root exceeds its section size limit",
                    rootBytes, helperBytes, null));
                continue;
            }
            if (!root.instructions().containsKey(root.address()) || !helper.instructions().containsKey(helper.address())) {
                result.add(new Evidence(site.root(), "unverified", "root or helper entry could not be disassembled",
                    rootBytes, helperBytes, null));
                continue;
            }
            Instruction transfer = reachableTransfer(root, helper.address(), arm);
            if (transfer == null) {
                result.add(new Evidence(site.root(), "unverified",
                    "no direct transfer to the helper entry was found from the root entry",
                    rootBytes, helperBytes, null));
                continue;
            }
            String transferType = flow(transfer.mnemonic(), arm) == Flow.CALL ? "call" : "tail branch";
            result.add(new Evidence(site.root(), "verified",
                "direct " + transferType + " to the helper is statically reachable from the root entry",
                rootBytes, helperBytes, transfer.address() - root.address()));
        }
        return List.copyOf(result);
    }

    private static List<Evidence> unavailable(List<Selection> selected, String reason) {
        return selected.stream().map(site -> new Evidence(site.root(), "unavailable", reason, null, null, null)).toList();
    }

    private static String disassemble(Path library, String objdump, List<Selection> selected)
            throws IOException, InterruptedException {
        var command = new ArrayList<>(List.of(objdump, "-h", "-d", "--no-symbolize-operands"));
        for (Selection site : selected) {
            command.add("--section=" + site.rootSection());
            command.add("--section=" + site.helperSection());
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
            if (process.exitValue() != 0)
                throw new IOException("linked disassembly exited with status " + process.exitValue());
            return new String(bytes, StandardCharsets.UTF_8);
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
            if (flow == Flow.NEXT || flow == Flow.CONDITIONAL)
                pending.add(address + instruction.bytes());
        }
        return null;
    }

    private static Flow flow(String mnemonic, boolean arm) {
        if (mnemonic.startsWith(".") || mnemonic.startsWith("<")) return Flow.STOP;
        if (arm) {
            if (mnemonic.startsWith("b.") || mnemonic.startsWith("bc.")) return Flow.CONDITIONAL;
            return switch (mnemonic) {
                case "bl" -> Flow.CALL;
                case "b" -> Flow.TAIL_BRANCH;
                case "cbz", "cbnz", "tbz", "tbnz" -> Flow.CONDITIONAL;
                case "br", "braa", "braaz", "brab", "brabz", "blr", "blraa", "blraaz", "blrab", "blrabz",
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
