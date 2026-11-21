#!/usr/bin/env python3
"""Build, verify, and measure the analysis profiles on all supported native targets."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
import zipfile


ROOT = Path(__file__).resolve().parents[3]
BUILD = ROOT / "build"
RESOURCE_ROOT = "META-INF/native-obfuscator/"
TARGETS = {
    "x86_64-linux": ("libprotected.so", "elf64-x86-64"),
    "aarch64-linux": ("libprotected.so", "elf64-littleaarch64"),
    "x86_64-windows": ("protected.dll", "coff-x86-64"),
    "aarch64-windows": ("protected.dll", "coff-arm64"),
}
SECTION = re.compile(r"\s*\d+\s+(\S+)\s+([0-9a-fA-F]+)\s+[0-9a-fA-F]+\s+TEXT(?:\s.*)?")
PASS_TOTAL = re.compile(r"^target=(\S+) native-obfuscator: \S+ .*?\btransformed=([0-9]+)", re.MULTILINE)


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def java_tool(name):
    java_home = os.environ.get("JAVA_HOME")
    executable = name + (".exe" if os.name == "nt" else "")
    return Path(java_home) / "bin" / executable if java_home else name


def run(command, log, *, env=None, timeout=900, check=True):
    argv = [str(value) for value in command]
    try:
        result = subprocess.run(argv, cwd=log.parent, env=env, capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired as failure:
        log.with_suffix(".stdout").write_bytes(failure.stdout or b"")
        log.with_suffix(".stderr").write_bytes(failure.stderr or b"")
        raise RuntimeError(f"Command timed out after {timeout} seconds. See {log}.*") from failure
    log.with_suffix(".stdout").write_bytes(result.stdout)
    log.with_suffix(".stderr").write_bytes(result.stderr)
    if check and result.returncode:
        raise RuntimeError(f"Command exited {result.returncode}. See {log}.*\n{argv}")
    return result


def read_json_lines(result):
    return [json.loads(line) for line in result.stdout.splitlines() if line.strip()]


def tool_version(command, log, *, check=True):
    result = run(command, log, check=check, timeout=30)
    output = result.stdout + result.stderr
    return {"status": result.returncode, "output": output.decode("utf-8", errors="replace").strip()}


def host_target(java, work):
    result = run([java, "-XshowSettings:properties", "-version"], work / "java-properties", timeout=30)
    settings = dict(re.findall(r"^\s*(os.arch|os.name)\s*=\s*(.+)$",
                               result.stderr.decode("utf-8", errors="replace"), re.MULTILINE))
    arch = {"amd64": "x86_64", "x86_64": "x86_64", "arm64": "aarch64", "aarch64": "aarch64"}.get(
        settings.get("os.arch", "").strip().lower()
    )
    system = settings.get("os.name", "").strip().lower()
    if system.startswith("windows"):
        system = "windows"
    target = f"{arch}-{system}"
    require(target in TARGETS, f"Unsupported Java host. See {work / 'java-properties.stderr'}")
    return target


def jar_stats(path, archive):
    entries = archive.infolist()
    names = {entry.filename for entry in entries}
    require(len(entries) == len(names), f"Duplicate entries in {path}")
    analysis = [entry for entry in entries
                if entry.filename.startswith(RESOURCE_ROOT) and "/analysis/" in entry.filename]
    return {
        "archiveBytes": path.stat().st_size,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "compressedEntryBytes": sum(entry.compress_size for entry in entries),
        "expandedEntryBytes": sum(entry.file_size for entry in entries),
        "entries": len(entries),
        "analysisResourceBytes": sum(entry.file_size for entry in analysis),
        "analysisResourceEntries": len(analysis),
    }


def native_stats(archive, directory, objdump):
    contents = archive.infolist()
    results = {}
    for target, (filename, file_format) in TARGETS.items():
        resource = f"/{target}/{filename}"
        matches = [entry for entry in contents
                   if entry.filename.startswith(RESOURCE_ROOT) and entry.filename.endswith(resource)]
        require(len(matches) == 1, f"Expected one packaged library for {target}")
        entry = matches[0]
        output = directory / f"{target}-{filename}"
        output.write_bytes(archive.read(entry))
        result = run([objdump, "-h", output], directory / f"{target}-sections", timeout=30)
        sections_text = result.stdout.decode("utf-8")
        require(any(line.rstrip().endswith(f"file format {file_format}") for line in sections_text.splitlines()),
                f"Unexpected native library format for {target}")
        sections = {match[1]: int(match[2], 16) for line in sections_text.splitlines()
                    if (match := SECTION.fullmatch(line))}
        executable_bytes = sum(sections.values())
        require(0 < executable_bytes <= entry.file_size, f"Invalid executable section sizes for {target}")
        results[target] = {
            "libraryBytes": entry.file_size,
            "compressedLibraryBytes": entry.compress_size,
            "executableBytes": executable_bytes,
            "executableSections": sections,
            "analysisBindingSection": bool(re.search(r"^\s*\d+\s+\.noab\s+", sections_text, re.MULTILINE)),
        }
    return results


def run_application(java, java_tmp, jar, log):
    result = run([java, f"-Djava.io.tmpdir={java_tmp}", "-Xverify:all", "-Xcheck:jni",
                  "--enable-native-access=ALL-UNNAMED", "-jar", jar], log, timeout=120)
    require(b"WARNING in native method" not in result.stdout + result.stderr, f"JNI warnings. See {log}.*")
    return result.stdout


def check_analysis(profile, seed, build_reports, verification, jar_metrics, native_metrics, selected):
    reports = [item for item in build_reports if "analysisResistance" in item]
    if profile == "off":
        require(not reports and not verification.get("analysis") and not verification.get("nativeHelpers"),
                "Off profile emitted analysis reports")
        require(jar_metrics["analysisResourceEntries"] == 0, "Off profile emitted analysis resources")
        for metrics in native_metrics.values():
            require(not metrics["analysisBindingSection"], "Off profile emitted a binding section")
            require(not any(name.startswith((".nar", ".nah")) for name in metrics["executableSections"]),
                    "Off profile emitted a helper section")
        return []

    require(len(reports) == 1, f"Expected one protect analysis report for {profile}")
    report = reports[0]
    analysis = verification["analysis"]
    require(report["analysisResistance"] == profile and report["analysisSeed"] == seed, "Protect analysis settings")
    require(report["artifact"]["bytes"] == jar_metrics["archiveBytes"]
            and report["artifact"]["sha256"] == verification["stagedSha256"] == jar_metrics["sha256"],
            "Packaged artifact evidence differs from final JAR")
    require(analysis["profile"] == profile and analysis["seed"] == seed and analysis["resourcesVerified"],
            "Verify analysis settings or resource checks")
    require(set(analysis["anchoredNativeTargets"]) == set(TARGETS), "Unverified native analysis binding")
    require(report["resourceBytes"] == analysis["expectedResourceBytes"] == jar_metrics["analysisResourceBytes"]
            and report["resourceEntries"] == analysis["expectedResourceEntries"] == jar_metrics["analysisResourceEntries"],
            "Analysis resource measurements differ from protect or verify")
    require(0 < report["resourceBytes"] <= report["resourceBudget"]
            and 1 < report["resourceEntries"] <= report["entryBudget"], "Analysis resource budget exceeded")

    build_targets = {item["target"]: item for item in report["targets"]}
    require(len(report["targets"]) == len(TARGETS) and set(build_targets) == set(TARGETS),
            "Protect analysis report did not measure every target")
    verify_targets = {item["target"]: item["helpers"] for item in verification.get("nativeHelpers", [])}
    planned = sorted({item["method"] for item in build_reports if item.get("result") == "native-helper-planned"})
    helper_count = report["nativeHelperMethods"]
    require(len(planned) == helper_count and set(planned) <= set(selected), "Protect helper planning report")
    if profile == "maximum":
        require(helper_count > 0 and set(verify_targets) == set(TARGETS),
                "Maximum must outline and verify helpers for every target")
    else:
        require(helper_count == 0 and not any(verify_targets.values()), "Balanced must not outline helpers")

    observed_roots = None
    for target, metrics in native_metrics.items():
        measured = build_targets[target]
        require(metrics["analysisBindingSection"], f"Missing binding section for {target}")
        require(metrics["libraryBytes"] == measured["libraryBytes"]
                and metrics["executableBytes"] == measured["executableBytes"], f"Native size mismatch for {target}")
        helpers = measured["helpers"]
        require(len(helpers) == helper_count and helpers == verify_targets.get(target, []),
                f"Protect and verify helper evidence differ for {target}")
        sections = metrics["executableSections"]
        root_sections = [size for name, size in sections.items() if name.startswith(".nar")]
        helper_sections = [size for name, size in sections.items() if name.startswith(".nah")]
        require(len(root_sections) == len(helper_sections) == helper_count, f"Helper section count for {target}")
        require(sorted(root_sections) == sorted(item["rootBytes"] for item in helpers)
                and sorted(helper_sections) == sorted(item["helperBytes"] for item in helpers),
                f"Helper section measurements differ from disassembly for {target}")
        roots = {item["root"] for item in helpers}
        require(len(roots) == helper_count, f"Duplicate native helper roots for {target}")
        require(observed_roots is None or observed_roots == roots, f"Outlined methods differ across targets for {profile}")
        observed_roots = roots
        for item in helpers:
            require(item["status"] == "verified" and 0 <= item["edgeOffset"] < item["rootBytes"],
                    f"Final linked helper edge is unverified for {target}: {item}")
        metrics["helpers"] = helpers
    return planned


def measure_case(profile, compiler_args, *, work, tool, java, java_tmp, original, baseline, host, args, env):
    label = f"{profile}-llvm" if compiler_args else profile
    directory = work / label
    directory.mkdir()
    jar = directory / "protected.jar"
    common = ["--input", original, "--include", "Arithmetic#calc*", "--unsupported", "retain",
              "--padding", "off", "--target", "all", "--analysis-resistance", profile]
    if profile != "off":
        common.extend(["--analysis-seed", args.analysis_seed])
    started = time.perf_counter()
    build = run([*tool, "protect", *common, "--output", jar, "--zig", args.zig, *compiler_args],
                directory / "protect", env=env)
    build_seconds = time.perf_counter() - started

    started = time.perf_counter()
    verified = run([*tool, "verify", *common, "--staged", jar], directory / "verify", env=env)
    verify_seconds = time.perf_counter() - started
    verify_report = read_json_lines(verified)[-1]
    verification = verify_report["verification"]
    require(verify_report["status"] == "verified" and verification["structurallyValid"],
            f"Structural verification failed for {label}")
    require(verification["nativeLibraryCount"] == len(TARGETS) and set(verification["nativeTargets"]) == set(TARGETS),
            f"Structural verification did not check all targets for {label}")

    build_reports = read_json_lines(build)
    selected = sorted({item["method"] for item in build_reports if item.get("result") == "selected"})
    require(selected and selected == sorted(verification["verifiedMethods"]),
            f"Build selected methods differ from verify results for {label}")
    with zipfile.ZipFile(jar) as archive:
        jar_metrics = jar_stats(jar, archive)
        native_metrics = native_stats(archive, directory, args.llvm_objdump)
    planned = check_analysis(profile, args.analysis_seed, build_reports, verification, jar_metrics, native_metrics,
                             selected)
    output = run_application(java, java_tmp, jar, directory / "application")
    require(output == baseline, f"Application output differs for {label}. See {directory / 'application.stdout'}")
    result = {
        "analysisProfile": profile,
        "compileSeconds": round(build_seconds, 3),
        "verifySeconds": round(verify_seconds, 3),
        "selectedMethods": selected,
        "nativeHelperMethods": len(planned),
        "plannedHelperMethods": planned,
        "verifiedNativeTargets": sorted(verification["nativeTargets"]),
        "runtimeComparedTarget": host,
        "runtimeMatchesOriginal": True,
        "jar": jar_metrics,
        "nativeTargets": native_metrics,
    }
    if compiler_args:
        transformed = dict.fromkeys(TARGETS, 0)
        for target, sites in PASS_TOTAL.findall(build.stderr.decode("utf-8", errors="replace")):
            transformed[target] += int(sites)
        require(all(transformed.values()), f"LLVM emitted no transforms for at least one target: {transformed}")
        result["llvmTransformedSites"] = transformed
    print(f"{label}: verified all {len(TARGETS)} targets; matched runtime on {host}; {len(planned)} helper methods", flush=True)
    return result


def existing_file(parser, option, value):
    path = Path(value).resolve()
    if not path.is_file():
        parser.error(f"{option} must point to an existing file")
    return path


def arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("distribution", type=Path, nargs="?", default=BUILD / "install" / "native-obfuscator")
    parser.add_argument("--zig", required=True, help="path to the Zig executable")
    parser.add_argument("--llvm-objdump", default=os.environ.get("LLVM_OBJDUMP") or shutil.which("llvm-objdump"),
                        help="LLVM objdump executable (or set LLVM_OBJDUMP)")
    parser.add_argument("--analysis-seed", default="18446744073709551615", help="unsigned 64-bit decimal seed")
    parser.add_argument("--clang", help="Clang executable for an optional maximum profile LLVM build")
    parser.add_argument("--llvm-opt", help="opt executable for the optional LLVM build")
    parser.add_argument("--llvm-plugin", help="trusted native pass plugin for the optional LLVM build")
    parser.add_argument("--llvm-passes", default="native-encode<seed=42;max=4;prefix=method_>")
    args = parser.parse_args()
    args.distribution = args.distribution.resolve()
    if not (args.distribution / "lib" / "native-obfuscator.jar").is_file():
        parser.error("Build installDist or pass an installed distribution directory")
    if not args.llvm_objdump:
        parser.error("Pass --llvm-objdump or set LLVM_OBJDUMP")
    args.zig = existing_file(parser, "--zig", args.zig)
    args.llvm_objdump = existing_file(parser, "--llvm-objdump", args.llvm_objdump)
    if not re.fullmatch(r"[0-9]{1,20}", args.analysis_seed) or int(args.analysis_seed) >= 1 << 64:
        parser.error("--analysis-seed must be an unsigned 64-bit decimal integer")
    args.analysis_seed = str(int(args.analysis_seed))
    llvm_options = [args.clang, args.llvm_opt, args.llvm_plugin]
    if any(llvm_options) and not all(llvm_options):
        parser.error("Pass --clang, --llvm-opt, and --llvm-plugin together")
    if args.clang:
        args.clang = existing_file(parser, "--clang", args.clang)
        args.llvm_opt = existing_file(parser, "--llvm-opt", args.llvm_opt)
        args.llvm_plugin = existing_file(parser, "--llvm-plugin", args.llvm_plugin)
    return args


def main():
    args = arguments()
    BUILD.mkdir(exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="analysis-targets-", dir=BUILD))
    print(f"Writing cross-target artifacts to {work}", flush=True)
    java_tmp = work / "tmp"
    java_tmp.mkdir()
    java = java_tool("java")
    javac = java_tool("javac")
    env = {**os.environ, "LLVM_OBJDUMP": str(args.llvm_objdump), "LC_ALL": "C"}
    tool = [java, f"-Djava.io.tmpdir={java_tmp}", "-cp", args.distribution / "lib" / "*", "dev.nativeobfuscator.Main"]
    host = host_target(java, work)
    versions = {
        "java": tool_version([java, "-version"], work / "java-version"),
        "javac": tool_version([javac, "-version"], work / "javac-version"),
        "zig": tool_version([args.zig, "version"], work / "zig-version"),
        "llvmObjdump": tool_version([args.llvm_objdump, "--version"], work / "llvm-objdump-version"),
    }
    compiler_args = []
    if args.clang:
        versions["clang"] = tool_version([args.clang, "--version"], work / "clang-version")
        versions["llvmOpt"] = tool_version([args.llvm_opt, "--version"], work / "llvm-opt-version", check=False)
        versions["llvmOpt"]["sha256"] = hashlib.sha256(args.llvm_opt.read_bytes()).hexdigest()
        versions["llvmPluginSha256"] = hashlib.sha256(args.llvm_plugin.read_bytes()).hexdigest()
        compiler_args = ["--clang", args.clang, "--llvm-opt", args.llvm_opt, "--llvm-plugin", args.llvm_plugin,
                         "--profile", "balanced", "--llvm-passes", args.llvm_passes]

    classes = work / "classes"
    classes.mkdir()
    original = work / "original.jar"
    run([javac, "--release", "21", "-encoding", "UTF-8", "-Xlint:all", "-Werror", "-d", classes,
         ROOT / "src" / "test" / "fixtures" / "Arithmetic.java"], work / "compile-fixture")
    run([java_tool("jar"), "--create", "--file", original, "--main-class", "Arithmetic", "-C", classes, "."],
        work / "package-fixture")
    baseline = run_application(java, java_tmp, original, work / "original-application")
    require(baseline.strip(), "Arithmetic fixture did not produce output")
    with zipfile.ZipFile(original) as archive:
        original_stats = jar_stats(original, archive)

    cases = [("off", "off", []), ("balanced", "balanced", []), ("maximum", "maximum", [])]
    if compiler_args:
        cases.extend([("off-llvm", "off", compiler_args), ("maximum-llvm", "maximum", compiler_args)])
    results = {}
    for label, profile, flags in cases:
        results[label] = measure_case(profile, flags, work=work, tool=tool, java=java, java_tmp=java_tmp,
                                      original=original, baseline=baseline, host=host, args=args, env=env)
        require(results[label]["selectedMethods"] == results["off"]["selectedMethods"],
                f"Application method selection changed in {label}")
        if profile != "off":
            comparison = "off-llvm" if flags else "off"
            results[label]["sizeComparisonBaseline"] = comparison
            results[label]["jarByteGrowthFromOff"] = (results[label]["jar"]["archiveBytes"]
                                                       - results[comparison]["jar"]["archiveBytes"])
            for target, metrics in results[label]["nativeTargets"].items():
                before = results[comparison]["nativeTargets"][target]
                metrics["libraryByteGrowthFromOff"] = metrics["libraryBytes"] - before["libraryBytes"]
                metrics["executableByteGrowthFromOff"] = metrics["executableBytes"] - before["executableBytes"]
    summary = {
        "result": "pass",
        "hostTarget": host,
        "crossBuildOnlyTargets": sorted(set(TARGETS) - {host}),
        "analysisSeed": args.analysis_seed,
        "versions": versions,
        "originalJar": original_stats,
        "originalApplicationOutputSha256": hashlib.sha256(baseline).hexdigest(),
        "profiles": results,
    }
    if compiler_args:
        summary["llvmPasses"] = args.llvm_passes
    output = work / "summary.json"
    output.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(f"Cross-target analysis checks passed. Summary at {output}")


if __name__ == "__main__":
    main()
