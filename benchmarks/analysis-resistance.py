#!/usr/bin/env python3
"""Compare original Java and three analysis profiles on one local JAR fixture."""

import argparse
from collections import Counter, defaultdict
import hashlib
import json
import math
import os
from pathlib import Path
import random
import re
import shutil
from statistics import median
import subprocess
import tempfile
from time import perf_counter_ns
import zipfile


ROOT = Path(__file__).resolve().parents[1]
FIXTURE = Path(__file__).with_name("analysis-workload.java")
NATIVE_METHODS = ["step(I)I", "mix(II)I", "batch(III)I", "conditional(I)I", "accumulate(I)I", "arrayUpdate([III)I"]
RETAINED = "retainedWithMonitor([II)I"
EXCLUDED = "excluded(I)I"
OWNER = "AnalysisWorkload$Kernel"
TARGETS = {
    "x86_64-linux": "elf64-x86-64",
    "aarch64-linux": "elf64-littleaarch64",
    "x86_64-windows": "coff-x86-64",
    "aarch64-windows": "coff-arm64",
}
BATCH = 32
WORKLOADS = ("step", "per_call", "batched_32")
SECTION = re.compile(r"\s*\d+\s+(\S+)\s+([0-9a-f]+)\s+([0-9a-f]+)\s+TEXT(?:\s.*)?", re.I)
INSTRUCTION = re.compile(r"\s*([0-9a-f]+):\s+([0-9a-f]{8}\s+|(?:[0-9a-f]{2}\s+)+)(\S+)\s*(.*)", re.I)
LABEL = re.compile(r"([0-9a-f]+) <.+>:", re.I)
STATIC_SYMBOL = re.compile(r"[0-9a-f]+\s+.*?\s(\.[\w.]+)\s+[0-9a-f]+\s+(\S+)", re.I)
DYNAMIC_SYMBOL = re.compile(r"[0-9a-f]+\s+[gw]\s+.*?\s(\.[\w.]+)\s+[0-9a-f]+\s+(.*)", re.I)
IMMEDIATE = re.compile(r"(?:\$|#)(-?(?:0x[0-9a-f]+|[0-9]+))\b", re.I)
BRANCH_TARGET = re.compile(r"(?:^|,)\s*0x([0-9a-f]+)(?=\s|$)", re.I)
LLVM_PASS_TOTAL = re.compile(r"^target=(\S+) native-obfuscator: (native-\S+) .*?\btransformed=(\d+)\b", re.M)
LLVM_GROWTH = re.compile(r"^(\S+) executable bytes (\d+) -> (\d+), library bytes (\d+) -> (\d+)", re.M)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def run(argv, log, env, timeout=180):
    command = [str(argument) for argument in argv]
    start = perf_counter_ns()
    try:
        result = subprocess.run(command, env=env, capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired as error:
        log.with_suffix(".stdout").write_bytes(error.stdout or b"")
        log.with_suffix(".stderr").write_bytes(error.stderr or b"")
        raise ValueError(f"Command timed out after {timeout} seconds. See {log}.*") from error
    elapsed = perf_counter_ns() - start
    log.with_suffix(".stdout").write_bytes(result.stdout)
    log.with_suffix(".stderr").write_bytes(result.stderr)
    require(result.returncode == 0, f"Command exited {result.returncode}. See {log}.*")
    return result.stdout.decode("utf-8", errors="replace"), result.stderr.decode("utf-8", errors="replace"), elapsed


def java_tool(name):
    home = os.environ.get("JAVA_HOME")
    return Path(home) / "bin" / (name + (".exe" if os.name == "nt" else "")) if home else name


def unsigned(bits):
    def parse(value):
        if not re.fullmatch(r"[0-9]+", value) or int(value) >= 1 << bits:
            raise argparse.ArgumentTypeError(f"expected an unsigned {bits}-bit decimal integer")
        return int(value)

    return parse


def arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("distribution", type=Path, nargs="?", default=ROOT / "build/install/native-obfuscator")
    parser.add_argument("--zig", default=shutil.which("zig"), help="Zig path, required on Windows and for cross-builds")
    parser.add_argument("--llvm-objdump", default=os.environ.get("LLVM_OBJDUMP") or shutil.which("llvm-objdump"),
                        help="LLVM objdump path (or set LLVM_OBJDUMP)")
    parser.add_argument("--target", choices=TARGETS, action="append", default=[], help="additional cross-build target")
    parser.add_argument("--analysis-seed", type=unsigned(64), action="append", help="repeatable, defaults to 0 and 42")
    parser.add_argument("--input-seed", type=unsigned(32), action="append",
                        help="repeatable probe seed, defaults to 637 and 2309737967. First seed is timed")
    parser.add_argument("--startup-runs", type=int, default=7, help="fresh JVMs per case (default: 7)")
    parser.add_argument("--forks", type=int, default=3, help="fresh steady-state JVMs per case (default: 3)")
    parser.add_argument("--warmups", type=int, default=5, help="warmup iterations per workload and JVM (default: 5)")
    parser.add_argument("--trials", type=int, default=9, help="timed samples per workload and JVM (default: 9)")
    parser.add_argument("--iterations", type=int, default=524288,
                        help="arithmetic updates per sample, multiple of 32 (default: 524288)")
    parser.add_argument("--clang", help="Clang path for an optional matched LLVM build")
    parser.add_argument("--llvm-opt", help="matching opt executable")
    parser.add_argument("--llvm-plugin", help="matching local LLVM pass plugin")
    parser.add_argument("--max-growth", type=int, help="cap executable growth against each LLVM build's frontend baseline")
    parser.add_argument("--max-runtime-overhead-percent", type=float,
                        help="optional per-workload steady-state median limit relative to native off")
    parser.add_argument("--max-startup-overhead-ms", type=float,
                        help="optional first invocation median increase limit relative to native off")
    llvm = parser.add_mutually_exclusive_group()
    llvm.add_argument("--llvm-profile", choices=("compatibility", "balanced", "aggressive", "maximum"))
    llvm.add_argument("--llvm-passes", help="explicit pipeline shared by every protected case")
    args = parser.parse_args()
    args.distribution = args.distribution.resolve()
    if not (args.distribution / "lib/native-obfuscator.jar").is_file():
        parser.error("run installDist or pass an installed distribution directory")
    if not args.llvm_objdump:
        parser.error("pass --llvm-objdump or set LLVM_OBJDUMP")
    compiler = (args.clang, args.llvm_opt, args.llvm_plugin)
    if (any(compiler) or args.llvm_profile or args.llvm_passes or args.max_growth is not None) and not all(compiler):
        parser.error("pass --clang, --llvm-opt, and --llvm-plugin together")
    if args.max_growth is not None and (args.max_growth < 0 or args.llvm_profile == "compatibility"):
        parser.error("--max-growth requires a non-negative percentage and an LLVM transformation profile")
    for limit in (args.max_runtime_overhead_percent, args.max_startup_overhead_ms):
        if limit is not None and (not math.isfinite(limit) or limit < 0):
            parser.error("overhead limits must be finite and non-negative")
    for option in ("zig", "llvm_objdump", "clang", "llvm_opt", "llvm_plugin"):
        value = getattr(args, option)
        if value:
            path = Path(shutil.which(value) or value).resolve()
            if not path.is_file():
                parser.error(f"--{option.replace('_', '-')} must name an existing file")
            setattr(args, option, path)
    if min(args.startup_runs, args.forks, args.warmups, args.trials) < 1:
        parser.error("startup runs, forks, warmups, and trials must be positive")
    if not BATCH <= args.iterations < 1 << 31 or args.iterations % BATCH:
        parser.error(f"--iterations must be a positive signed 32-bit multiple of {BATCH}")
    args.analysis_seed = list(dict.fromkeys(args.analysis_seed or [0, 42]))
    args.input_seed = list(dict.fromkeys(args.input_seed or [637, 2309737967]))
    return args


def flow(mnemonic, arm):
    if arm:
        if mnemonic.startswith(("b.", "bc.")) or mnemonic in {"cbz", "cbnz", "tbz", "tbnz"}:
            return "conditional"
        if mnemonic.startswith("bl"):
            return "call"
        if mnemonic == "b" or mnemonic in {"br", "braa", "braaz", "brab", "brabz"}:
            return "branch"
        if mnemonic.startswith(("ret", "eret")) or mnemonic in {"brk", "udf", "hlt"}:
            return "stop"
    else:
        if mnemonic in {"call", "callq", "calll", "callw", "lcall"}:
            return "call"
        if mnemonic in {"jmp", "jmpq", "jmpl", "jmpw", "ljmp"}:
            return "branch"
        if mnemonic.startswith(("j", "loop")):
            return "conditional"
        if mnemonic.startswith(("ret", "lret", "iret")) or mnemonic in {"int3", "ud0", "ud1", "ud2", "hlt"}:
            return "stop"
    return "next"


def static_code_metrics(instructions, labels):
    known = set(instructions)
    starts = labels & known
    result = Counter(dict.fromkeys(["instructions", "directConditionalBranches", "directUnconditionalBranches",
                                    "indirectBranches", "directCalls", "indirectCalls", "blockLeaderEstimate"], 0))
    result["instructions"] = len(known)
    for address, (size, kind, dest) in instructions.items():
        if kind == "call":
            result["directCalls" if dest is not None else "indirectCalls"] += 1
        if kind in {"conditional", "branch"}:
            count = "directConditionalBranches" if kind == "conditional" else "directUnconditionalBranches"
            result[count if dest is not None else "indirectBranches"] += 1
            if dest in known:
                starts.add(dest)
        if kind in {"conditional", "branch", "stop"} and address + size in known:
            starts.add(address + size)
    if known:
        starts.add(min(known))
    result["blockLeaderEstimate"] = len(starts)
    return dict(result)


def score_literals(observations, truth):
    matches = observations.keys() & truth
    return {
        "fixtureLiteralCount": len(truth),
        "distinctImmediateCandidates": len(observations),
        "distinctFixtureLiteralsSeen": len(matches),
        "matchedLiteralOccurrences": {f"0x{value:08x}": observations[value] for value in sorted(matches)},
        "missingFixtureLiterals": [f"0x{value:08x}" for value in sorted(truth - matches)],
    }


def arm_move_wide(raw):
    # Decode the instruction word because objdump can print MOVZ and MOVN as MOV.
    parts = raw.split()
    word = int(parts[0], 16) if len(parts) == 1 else int.from_bytes(bytes.fromhex(raw), "little")
    opcode = (word >> 29) & 3
    register = word & 31
    if word & 0x9fc00000 != 0x12800000 or opcode == 1 or register == 31:
        return None
    shift = ((word >> 21) & 1) * 16
    value = ((word >> 5) & 0xffff) << shift
    if opcode == 0:  # MOVN initializes with the complemented immediate.
        value ^= 0xffffffff
    patch_mask = (0xffff << shift) if opcode == 3 else 0
    return register, patch_mask, value


def move_wide_literals(moves, entries):
    observations = Counter()
    for address, (register, patch_mask, value) in moves.items():
        if not patch_mask or address in entries:
            continue
        initial = moves.get(address - 4)
        if initial is not None and initial[:2] == (register, 0):
            observations[(initial[2] & ~patch_mask) | value] += 1
    return observations


def bytecode_metrics(disassembly, truth):
    opcodes = re.findall(r"^\s*\d+:\s+(\w+)", disassembly, re.M)
    observations = Counter(int(value) & 0xffffffff
                           for value in re.findall(r"\bldc(?:_w)?\s+#\d+\s+// int (-?\d+)", disassembly))
    return {
        "kernelClassInstructions": len(opcodes),
        "kernelClassBranchInstructions": sum(op.startswith(("if", "goto")) or op.endswith("switch") for op in opcodes),
        "kernelClassInvokeInstructions": sum(op.startswith("invoke") for op in opcodes),
        "nativeMethodNames": sorted(re.findall(r"\bnative\s+\S+\s+(\w+)\(", disassembly)),
        "integerLiteralRecall": score_literals(observations, truth),
    }


def symbol_metrics(lines, sections):
    table = ""
    static = set()
    exports = set()
    for line in lines:
        if line in {"SYMBOL TABLE:", "DYNAMIC SYMBOL TABLE:", "Export Table:"}:
            table = line
        elif line.startswith("Disassembly of section "):
            break
        elif table == "SYMBOL TABLE:" and (match := STATIC_SYMBOL.fullmatch(line)) and match[1] in sections:
            static.add(match[2])
        elif table == "DYNAMIC SYMBOL TABLE:" and (match := DYNAMIC_SYMBOL.fullmatch(line)):
            exports.add(match[2].split()[-1])
        elif table == "Export Table:" and (match := re.fullmatch(r"\s+\d+\s+0x[0-9a-f]+\s+(\S+)\s*", line, re.I)):
            exports.add(match[1])
    return {"definedExportNames": sorted(exports), "namedStaticCodeSymbols": sorted(static)}


def linked_metrics(disassembly, target, truth):
    lines = disassembly.splitlines()
    require(any(line.endswith(f"file format {TARGETS[target]}") for line in lines), f"Unexpected format for {target}")
    sections = {match[1]: (int(match[2], 16), int(match[3], 16)) for line in lines if (match := SECTION.fullmatch(line))}
    require(sections, f"No executable sections for {target}")
    instructions = defaultdict(dict)
    labels = defaultdict(set)
    arm = target.startswith("aarch64-")
    wide_moves = defaultdict(dict)
    observations = Counter()
    active = None
    for line in lines:
        if line.startswith("Disassembly of section "):
            name = line.removeprefix("Disassembly of section ").rstrip(":")
            active = name if name in sections else None
        elif active and (label := LABEL.fullmatch(line)):
            labels[active].add(int(label[1], 16))
        elif active and (match := INSTRUCTION.fullmatch(line)):
            address, size = int(match[1], 16), len("".join(match[2].split())) // 2
            section_size, section_start = sections[active]
            if match[3].startswith((".", "<")) or not section_start <= address <= section_start + section_size - size:
                continue
            mnemonic = match[3].lower()
            operands = re.split(r"\s+(?:#(?:\s|$)|//)", match[4], maxsplit=1)[0]
            kind = flow(mnemonic, arm)
            dest = BRANCH_TARGET.search(operands)
            instructions[active][address] = (size, kind, int(dest[1], 16) if dest else None)
            if arm and size == 4 and mnemonic in {"mov", "movn", "movz", "movk"}:
                if (move := arm_move_wide(match[2])) is not None:
                    wide_moves[active][address] = move
            for operand in IMMEDIATE.findall(operands):
                value = int(operand, 16 if "0x" in operand.lower() else 10)
                if -(1 << 31) <= value < 1 << 32:
                    observations[value & 0xffffffff] += 1

    metrics = {name: {"bytes": size, **static_code_metrics(instructions[name], labels[name])}
               for name, (size, _) in sections.items()}
    totals = Counter()
    for section in metrics.values():
        totals.update(section)
    require(totals["instructions"] > 0, f"No executable instructions decoded for {target}")
    helper_entries = {address: name for name, (_, address) in sections.items() if name.startswith(".nah")}
    helper_transfers = []
    for source, (_, entry) in sections.items():
        if not source.startswith((".nar", ".nah")):
            continue
        for address, (_, kind, destination) in sorted(instructions[source].items()):
            if kind in {"branch", "call"} and destination in helper_entries:
                helper_transfers.append({"sourceSection": source, "targetSection": helper_entries[destination],
                                         "edgeOffset": address - entry, "kind": kind})
    result = {
        "wholeLibraryExecutableTotals": dict(totals),
        "executableSections": metrics,
        "decodedHelperTransfers": helper_transfers,
        "symbols": symbol_metrics(lines, sections),
        "integerLiteralRecall": score_literals(observations, truth),
    }
    if arm:
        destinations = {dest for section in instructions.values() for _, kind, dest in section.values()
                        if kind in {"conditional", "branch", "call"} and dest is not None}
        reconstructed = Counter()
        for name, moves in wide_moves.items():
            reconstructed.update(move_wide_literals(moves, destinations | labels[name]))
        result["moveWideLiteralRecall"] = score_literals(reconstructed, truth)
    return result


def jar_metrics(jar, directory, targets, objdump, truth, env):
    stats = {"jarBytes": jar.stat().st_size, "jarSha256": hashlib.sha256(jar.read_bytes()).hexdigest()}
    bytecode, _, _ = run([java_tool("javap"), "-p", "-c", "-classpath", jar, OWNER], directory / "javap", env)
    stats["javaKernel"] = bytecode_metrics(bytecode, truth)
    with zipfile.ZipFile(jar) as archive:
        native = {}
        resources = [entry for entry in archive.infolist()
                     if entry.filename.startswith("META-INF/native-obfuscator/") and "/analysis/" in entry.filename]
        stats["analysisResourceEntries"] = len(resources)
        stats["analysisResourceBytes"] = sum(entry.file_size for entry in resources)
        for target in targets:
            suffix = "libprotected.so" if target.endswith("-linux") else "protected.dll"
            matches = [entry for entry in archive.infolist()
                       if entry.filename.startswith("META-INF/native-obfuscator/")
                       and entry.filename.endswith(f"/{target}/{suffix}")]
            require(len(matches) == 1, f"Expected one library in {jar} for {target}")
            entry = matches[0]
            binary = directory / f"{target}-{suffix}"
            binary.write_bytes(archive.read(entry))
            flags = ["-h", "-t", "-p", "-d", "--print-imm-hex"]
            if target.endswith("-linux"):
                flags.append("-T")
            asm, _, _ = run([objdump, *flags, binary], directory / f"{target}-objdump", env, timeout=30)
            native[target] = {"libraryBytes": entry.file_size, **linked_metrics(asm, target, truth)}
    stats["nativeTargets"] = native
    return stats


def checked_build(stdout, stderr, stats, profile, seed):
    records = [json.loads(line) for line in stdout.splitlines() if line.strip()]
    decisions = [record for record in records if "method" in record and "result" in record]
    selected = sorted(record["method"] for record in decisions if record["result"] == "selected")
    application = [record for record in decisions if record["method"].startswith(f"{OWNER}#")]
    app_selected = [method for method in selected if method.startswith(f"{OWNER}#")]
    utilities = sorted(record["method"] for record in decisions if record["result"] == "analysis-utility")
    require(app_selected == sorted(f"{OWNER}#{method}" for method in NATIVE_METHODS), "Unexpected selected fixture methods")
    require([method for method in selected if method not in app_selected] == utilities, "Unexpected selected utilities")
    require(stats["javaKernel"]["nativeMethodNames"] == sorted(method.split("(")[0] for method in NATIVE_METHODS),
            "Packaged native declarations differ from the fixture selection")
    counts = Counter(record["result"] for record in application)
    require(counts["excluded"] == 1, "Explicit exclusion was not reported")
    retained = [record for record in application if record["result"] == "retained"]
    require(len(retained) == 1 and retained[0]["method"] == f"{OWNER}#{RETAINED}"
            and "opcode 194" in retained[0]["reason"], "Unexpected unsupported monitor diagnostic")
    reports = [item for item in records if "analysisResistance" in item]
    if profile == "off":
        require(not reports and not utilities and stats["analysisResourceEntries"] == 0, "Analysis output appeared in off")
    else:
        require(len(reports) == 1 and reports[0]["analysisResistance"] == profile, "Missing analysis profile report")
        report = reports[0]
        require(int(report["analysisSeed"]) == seed, "CLI reported a different analysis seed")
        require(report["selectedApplicationMethods"] == len(app_selected)
                and report.get("generatedUtilityMethods", 0) == len(utilities), "CLI method counts differ from decisions")
        require(report["resourceEntries"] == stats["analysisResourceEntries"]
                and report["resourceBytes"] == stats["analysisResourceBytes"], "Resource metrics differ from CLI")
        limit = report.get("nativeMethodLimit", 8 if profile == "maximum" else 0)
        depth = report.get("nativeHelpersPerMethod", 1 if profile == "maximum" else 0)
        helper_candidates = {"step(I)I", "mix(II)I"}
        if profile == "maximum":
            helper_candidates.add("arrayUpdate([III)I")
        roots = min(len(helper_candidates), limit) if depth else 0
        planned_roots = {record["method"] for record in application if record["result"] == "native-helper-planned"}
        require(len(planned_roots) == roots and planned_roots <= {f"{OWNER}#{method}" for method in helper_candidates},
                "Unexpected methods selected for native helpers")
        functions = roots * depth
        require(report["nativeHelperMethods"] == counts["native-helper-planned"] == roots, "Unexpected helper root count")
        require(report.get("nativeHelperFunctions", roots) == functions, "Unexpected helper function count")
        require(counts["native-helper-skipped"] == (len(app_selected) - roots if limit else 0), "Unexpected helper skip count")
        require({target["target"] for target in report["targets"]} == stats["nativeTargets"].keys(),
                "CLI target metrics differ from packaged targets")
        for target in report["targets"]:
            metrics = stats["nativeTargets"][target["target"]]
            sections = metrics["executableSections"]
            require(target["libraryBytes"] == metrics["libraryBytes"]
                    and target["executableBytes"] == metrics["wholeLibraryExecutableTotals"]["bytes"],
                    "Packaged size metrics differ from CLI")
            utility_sections = {name: sizes["bytes"] for name, sizes in sections.items() if name.startswith(".nou")}
            require(len(utility_sections) == len(utilities)
                    and sorted(utility_sections.values()) == sorted(target["utilityCodeBytes"].values()),
                    "Linked utility sections differ from the build report")
            metrics["detectableUtilitySections"] = utility_sections
            comparison = target.get("llvmComparison")
            if comparison is not None:
                require(comparison["executableChanged"] ==
                        (comparison["baselineExecutableSha256"] != comparison["executableSha256"]),
                        "LLVM executable fingerprints contradict the change result")
                metrics["llvmComparison"] = comparison
            helper_evidence = target["helpers"]
            require(len(helper_evidence) == roots
                    and all(helper["status"] == "verified" for helper in helper_evidence), "Unverified helper roots")
            require(sum(name.startswith(".nar") for name in sections) == roots
                    and sum(name.startswith(".nah") for name in sections) == functions, "Unexpected linked helper sections")
            edge_fields = ("sourceSection", "targetSection", "edgeOffset")
            decoded = {tuple(edge[key] for key in edge_fields) for edge in metrics["decodedHelperTransfers"]}
            require(len(decoded) >= functions, "Linked disassembly is missing planned helper transfers")
            for helper in helper_evidence:
                edges = helper.get("edges")
                if edges is not None:
                    require(len(edges) == depth, "CLI helper depth differs from the plan")
                    for edge in edges:
                        require(edge["status"] == "verified" and tuple(edge[key] for key in edge_fields) in decoded,
                                "CLI verified edge is missing from independent disassembly")
            metrics["verifiedHelperEvidence"] = helper_evidence
            metrics["verifiedHelperEdges"] = functions
        stats["analysisPlan"] = {key: value for key, value in report.items() if key not in {"artifact", "targets"}}
    for target, name, transformed in LLVM_PASS_TOTAL.findall(stderr):
        stats["nativeTargets"][target].setdefault("llvmTransformedSitesByPass", {})[name] = int(transformed)
    for target, baseline, current, baseline_library, current_library in LLVM_GROWTH.findall(stderr):
        stats["nativeTargets"][target]["llvmGrowth"] = {
            "frontendExecutableBytes": int(baseline), "transformedExecutableBytes": int(current),
            "frontendLibraryBytes": int(baseline_library), "transformedLibraryBytes": int(current_library),
        }
    eligible = counts["selected"]
    candidates = eligible + counts["retained"]
    stats["methodSelection"] = {"applicationCounts": dict(counts), "generatedUtilityCount": len(utilities),
                                "eligibleApplicationCoveragePercent": 100.0 * len(app_selected) / eligible,
                                "requestedApplicationCoveragePercent": 100.0 * len(app_selected) / candidates,
                                "decisions": decisions}


def samples(argv, phase, log, env, trials):
    stdout, stderr, elapsed = run(argv, log, env)
    require("WARNING in native method" not in stdout + stderr, f"JNI check warning. See {log}.*")
    matches = defaultdict(list)
    for line in stdout.splitlines():
        match = re.fullmatch(r"(probe|startup|step|per_call|batched_32) (\d+) ([0-9a-f]{8})", line)
        require(match, f"Unexpected workload output. See {log}.*")
        matches[match[1]].append((int(match[2]), match[3]))
    expected = WORKLOADS if phase == "steady" else (phase,)
    sample_count = trials if phase == "steady" else 1
    require(matches.keys() == set(expected) and all(len(value) == sample_count for value in matches.values()),
            f"Incomplete workload measurements. See {log}.*")
    checksums = {}
    measurements = {}
    for name, values in matches.items():
        checksum = {value[1] for value in values}
        require(len(checksum) == 1, f"Sample checksums differ. See {log}.*")
        checksums[name] = checksum.pop()
        measurements[name] = [value[0] for value in values]
    if phase == "steady":
        require(checksums["per_call"] == checksums["batched_32"], f"Batched recurrence differs. See {log}.*")
    return checksums, measurements, elapsed


def timed_cases(cases, args, work, jvm, env):
    rng = random.Random(0)
    measured = defaultdict(lambda: defaultdict(list))
    expected = {}
    sweeps = [("probe", seed, 1) for seed in args.input_seed]
    sweeps.extend([("startup", args.input_seed[0], args.startup_runs), ("steady", args.input_seed[0], args.forks)])
    for phase, seed, repeats in sweeps:
        for repeat in range(repeats):
            order = list(cases)
            rng.shuffle(order)
            for case in order:
                directory = work / case
                argv = list(jvm)
                if phase == "probe":
                    argv.extend(["-Xverify:all", "-Xcheck:jni"])
                argv.extend(["-jar", directory / "fixture.jar", phase, seed])
                if phase == "steady":
                    argv.extend([args.iterations, args.warmups, args.trials])
                log = directory / f"{phase}-{seed}-{repeat + 1}"
                checksum, timings, elapsed = samples(argv, phase, log, env, args.trials)
                reference = expected.setdefault((phase, seed), checksum)
                require(checksum == reference, f"Fixture behavior differs across cases. See {log}.*")
                if phase == "startup":
                    measured[case]["processMs"].append(elapsed / 1e6)
                    measured[case]["firstInvocationMs"].append(timings["startup"][0] / 1e6)
                if phase == "steady":
                    for name in WORKLOADS:
                        measured[case][name].append(median(timings[name]) / args.iterations)
    for case, stats in cases.items():
        timing = measured[case]
        stats["startup"] = {name: {"median": median(timing[name]), "samples": timing[name]}
                            for name in ("processMs", "firstInvocationMs")}
        stats["steadyState"] = {}
        for name in WORKLOADS:
            ns = median(timing[name])
            require(ns > 0, f"Zero steady-state measurement for {case} {name}")
            stats["steadyState"][name] = {
                "medianNsPerUpdate": ns,
                "updatesPerSecond": 1e9 / ns,
                "forkMedianNsPerUpdate": timing[name],
                "protectedCallsPerUpdate": (1 / BATCH if name == "batched_32" else 1) if case != "original" else 0,
            }
        per_call_ns = stats["steadyState"]["per_call"]["medianNsPerUpdate"]
        batched_ns = stats["steadyState"]["batched_32"]["medianNsPerUpdate"]
        stats["steadyState"]["perCallToBatchedTimeRatio"] = per_call_ns / batched_ns
    return {phase + "-" + str(seed): checksums for (phase, seed), checksums in expected.items()}


def overhead_checks(cases, args):
    baseline = cases["off"]
    violations = []
    for label, stats in cases.items():
        if stats.get("analysisProfile") not in {"balanced", "maximum"}:
            continue
        startup = stats["startup"]["firstInvocationMs"]["median"] - baseline["startup"]["firstInvocationMs"]["median"]
        steady = {}
        for name in WORKLOADS:
            reference = baseline["steadyState"][name]["medianNsPerUpdate"]
            steady[name] = 100 * (stats["steadyState"][name]["medianNsPerUpdate"] / reference - 1)
            limit = args.max_runtime_overhead_percent
            if limit is not None and steady[name] > limit:
                violations.append(f"{label} {name} overhead {steady[name]:.2f}% exceeds {limit:g}%")
        limit = args.max_startup_overhead_ms
        if limit is not None and startup > limit:
            violations.append(f"{label} first invocation increase {startup:.2f} ms exceeds {limit:g} ms")
        stats["overheadAgainstNativeOff"] = {"firstInvocationIncreaseMs": startup, "steadyStateIncreasePercent": steady}
    return violations


def main():
    args = arguments()
    build = ROOT / "build"
    build.mkdir(exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="analysis-benchmark-", dir=build))
    print(f"Writing analysis benchmark artifacts to {work}", flush=True)
    java_tmp = work / "tmp"
    java_tmp.mkdir()
    distribution = work / "distribution" / "lib"
    shutil.copytree(args.distribution / "lib", distribution)
    distribution_digest = hashlib.sha256((distribution / "native-obfuscator.jar").read_bytes()).hexdigest()
    env = {**os.environ, "LLVM_OBJDUMP": str(args.llvm_objdump), "LC_ALL": "C"}
    jvm = [java_tool("java"), f"-Djava.io.tmpdir={java_tmp}", "--enable-native-access=ALL-UNNAMED"]
    tool = [*jvm, "-cp", distribution / "*", "dev.nativeobfuscator.Main"]
    _, properties, _ = run([java_tool("java"), "-XshowSettings:properties", "-version"], work / "java-properties", env)
    values = {key: value.strip().lower()
              for key, value in re.findall(r"^\s*(os.arch|os.name)\s*=\s*(.+)$", properties, re.M)}
    arch = {"amd64": "x86_64", "arm64": "aarch64"}.get(values.get("os.arch"), values.get("os.arch"))
    system = values.get("os.name", "")
    host = f"{arch}-{'windows' if system.startswith('windows') else system}"
    require(host in TARGETS, f"Unsupported Java host {host}")
    targets = list(dict.fromkeys([host, *args.target]))
    require(args.zig or (host.endswith("-linux") and len(targets) == 1), "Pass --zig on Windows or for cross-builds")
    commands = {"java": [java_tool("java"), "-version"], "llvmObjdump": [args.llvm_objdump, "--version"]}
    if args.zig:
        commands["zig"] = [args.zig, "version"]
    elif not args.clang:
        commands["cc"] = [os.environ.get("CC") or "cc", "--version"]
    compiler = []
    if args.clang:
        commands["clang"] = [args.clang, "--version"]
        compiler = ["--clang", args.clang, "--llvm-opt", args.llvm_opt, "--llvm-plugin", args.llvm_plugin]
        if args.llvm_passes:
            compiler.extend(["--llvm-passes", args.llvm_passes])
        else:
            compiler.extend(["--profile", args.llvm_profile or "balanced"])
        if args.max_growth is not None:
            compiler.extend(["--max-growth", args.max_growth])
    versions = {}
    for name, command in commands.items():
        stdout, stderr, _ = run(command, work / f"version-{name}", env, timeout=30)
        lines = (stdout + stderr).strip().splitlines()
        versions[name] = next((line.strip() for line in lines if "version" in line.lower()), lines[0])
    if args.llvm_opt:
        for name, path in (("llvmOpt", args.llvm_opt), ("llvmPlugin", args.llvm_plugin)):
            versions[name] = {"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}

    original = work / "original"
    original.mkdir()
    classes = work / "classes"
    classes.mkdir()
    jar = original / "fixture.jar"
    run([java_tool("javac"), "--release", 21, "-encoding", "UTF-8", "-d", classes, FIXTURE], work / "javac", env)
    run([java_tool("jar"), "--create", "--date=2020-01-01T00:00:00Z", "--file", jar, "--main-class",
         "AnalysisWorkload", "-C", classes, "."], work / "jar", env)
    truth = {int(value, 16) for value in re.findall(r"\b0x[0-9a-f]{8}\b", FIXTURE.read_text(encoding="utf-8"), re.I)}
    require(truth, "No fixture literals to score")
    cases = {"original": jar_metrics(jar, original, [], args.llvm_objdump, truth, env)}
    require(cases["original"]["javaKernel"]["integerLiteralRecall"]["distinctFixtureLiteralsSeen"] == len(truth),
            "Original bytecode scan did not find all fixture literals")
    selections = ["--unsupported", "retain", "--exclude", f"{OWNER}#{EXCLUDED}"]
    for method in (*NATIVE_METHODS, RETAINED, EXCLUDED):
        selections.extend(["--include", f"{OWNER}#{method}"])
    common = ["--input", jar, "--padding", "off", *selections, *compiler]
    for target in targets:
        common.extend(["--target", target])
    if args.zig:
        common.extend(["--zig", args.zig])
    matrix = [("off", None)] + [(profile, seed) for seed in args.analysis_seed for profile in ("balanced", "maximum")]
    for profile, seed in matrix:
        label = profile if seed is None else f"{profile}-{seed}"
        directory = work / label
        directory.mkdir()
        output = directory / "fixture.jar"
        options = ["--analysis-resistance", profile]
        if seed is not None:
            options.extend(["--analysis-seed", seed])
        print(f"Building {label}", flush=True)
        stdout, stderr, elapsed = run([*tool, "protect", *common, *options, "--output", output],
                                      directory / "protect", env, timeout=900)
        stats = jar_metrics(output, directory, targets, args.llvm_objdump, truth, env)
        checked_build(stdout, stderr, stats, profile, seed)
        if args.max_growth is not None:
            require(all("llvmGrowth" in metrics for metrics in stats["nativeTargets"].values()),
                    "Missing per-target LLVM growth measurements")
        cases[label] = {"analysisProfile": profile, "analysisSeed": seed, "protectMs": elapsed / 1e6, **stats}

    print("Timing startup and steady state in fresh JVMs", flush=True)
    checksums = timed_cases(cases, args, work, jvm, env)
    violations = overhead_checks(cases, args)
    report = {
        "result": "budget-failed" if violations else "pass",
        "overheadBudgetViolations": violations,
        "maxRuntimeOverheadPercent": args.max_runtime_overhead_percent,
        "maxStartupOverheadMs": args.max_startup_overhead_ms,
        "hostTarget": host,
        "crossBuiltTargets": targets[1:],
        "toolVersions": versions,
        "distributionJarSha256": distribution_digest,
        "llvmCompilerOptions": [str(item) for item in compiler],
        "benchmarkScriptSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "fixtureSourceSha256": hashlib.sha256(FIXTURE.read_bytes()).hexdigest(),
        "analysisSeeds": args.analysis_seed,
        "probeInputSeeds": args.input_seed,
        "timedInputSeed": args.input_seed[0],
        "startupRuns": args.startup_runs,
        "steadyForks": args.forks,
        "steadyWarmupsPerFork": args.warmups,
        "steadySamplesPerFork": args.trials,
        "updatesPerSample": args.iterations,
        "batchUpdatesPerCall": BATCH,
        "matchedChecksums": checksums,
        "scope": {
            "startup": "Fresh JVMs. Process time runs from spawn through exit. First invocation includes kernel class initialization and, in protected cases, native registration. Filesystem caches may be warm.",
            "steadyState": "Median of per-fork medians after warmup. The per_call and batched_32 kernels run the same recurrence with one protected call per update or per 32 updates. JIT and native optimization can also affect the difference.",
            "nativeCode": "Linear disassembly of executable sections includes registration, toolchain code, and padding. Block-leader estimates count section entries, visible labels, local branch targets, and decoded successors after branches and returns. Decoded helper transfers match section entries directly, with reachability checked by the protection CLI.",
            "utilityDetection": "Counts executable .nou sections visible to llvm-objdump. These sections make the utility locations identifiable in the current artifact format.",
            "literalRecall": "Counts exact 32-bit fixture literals in javap ldc entries or native disassembly immediate operands. On ARM64, moveWideLiteralRecall separately counts values reconstructed from adjacent 32-bit MOVZ or MOVN followed by MOVK to the same register, with no known entry between them. These metrics report presence anywhere in the scanned code.",
        },
        "cases": cases,
    }
    output = work / "summary.json"
    output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"Analysis benchmark complete. Summary at {output}")
    if violations:
        raise SystemExit("\n".join(violations))


if __name__ == "__main__":
    main()
