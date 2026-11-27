#!/usr/bin/env python3
"""Run common Java-to-native and C/LLVM comparison workloads."""

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import random
import re
import shutil
from statistics import median
import subprocess
from time import perf_counter_ns
import zipfile


HERE = Path(__file__).resolve().parent
OWNER = "AnalysisWorkload$Kernel"
METHODS = (
    "step(I)I", "mix(II)I", "batch(III)I", "conditional(I)I", "accumulate(I)I",
    "arrayUpdate([III)I", "retainedWithMonitor([II)I",
)
WORKLOADS = ("step", "per_call", "batched_32")
BATCH = 32
LIMITS = {
    "maxBuildMs", "minBuildSuccessPercent", "minNativeCoveragePercent", "maxArtifactGrowthPercent",
    "maxStartupIncreaseMs", "maxRuntimeOverheadPercent", "maxPeakRssOverheadPercent",
}
WINDOWS = os.name == "nt"
SUFFIX = ".exe" if WINDOWS else ""


def require(condition, message):
    if not condition:
        raise ValueError(message)


def java_tool(name):
    home = os.environ.get("JAVA_HOME")
    return str(Path(home).resolve() / "bin" / (name + SUFFIX)) if home else name


def digest(file):
    with file.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def validate_limits(name, limits):
    require(isinstance(limits, dict) and limits.keys() <= LIMITS, f"Unknown budget for {name}")
    for key, value in limits.items():
        require(type(value) in (int, float) and 0 <= value < math.inf
                and (not key.startswith("min") or value <= 100), f"Invalid budget {name}.{key}")


def load_adapters(path):
    def unique_fields(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, f"Duplicate JSON field {key}")
            result[key] = value
        return result

    adapters = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_fields)
    require(isinstance(adapters, list), "The adapter file must contain a JSON array")
    names = {"java-original", "native-obfuscator", "c-original"}
    for adapter in adapters:
        require(isinstance(adapter, dict) and adapter.keys() <= {
            "name", "lane", "build", "version", "relativeTo", "limits",
        }, "Unknown adapter fields")
        name, lane = adapter.get("name"), adapter.get("lane")
        require(isinstance(name, str) and re.fullmatch(r"[A-Za-z][A-Za-z0-9_-]{0,63}", name),
                "Adapter name must contain only letters, digits, hyphens or underscores")
        require(name.casefold() not in names, f"Duplicate or reserved adapter name {name}")
        require(lane in ("java", "c"), f"Invalid lane for {name}")
        names.add(name.casefold())
        for key in ("build", "version"):
            argv = adapter.get(key)
            require(isinstance(argv, list) and argv and all(isinstance(item, str) and item for item in argv),
                    f"{name}.{key} must contain a nonempty argument array")
        require("{input}" in adapter["build"] and "{output}" in adapter["build"],
                f"{name}.build must contain separate {{input}} and {{output}} arguments")
        require(adapter.get("relativeTo", "") != name, f"{name} cannot compare against itself")
        limits = adapter.get("limits", {})
        validate_limits(name, limits)
        require(lane == "java" or "minNativeCoveragePercent" not in limits,
                f"Native JVM coverage is unavailable in the C lane ({name})")
    scopes = {"java-original": "java", "native-obfuscator": "java", "c-original": "c"}
    scopes.update((adapter["name"], adapter["lane"]) for adapter in adapters)
    for adapter in adapters:
        reference = adapter.get("relativeTo", f"{adapter['lane']}-original")
        require(isinstance(reference, str) and scopes.get(reference) == adapter["lane"],
                f"Invalid same-lane reference for {adapter['name']}")
    return adapters


class Runner:
    def __init__(self, env, timeout, time_executable):
        self.env = env
        self.timeout = timeout
        self.time_executable = time_executable

    def run(self, argv, log, *, cwd=None, rss=False):
        command = [str(arg) for arg in argv]
        log.with_suffix(".argv.json").write_text(json.dumps(command) + "\n", encoding="utf-8")
        memory = log.with_suffix(".rss")
        wrapped = rss and self.time_executable is not None
        if wrapped:
            command = [self.time_executable, "-f", "%M", "-o", str(memory), "--", *command]
        started = perf_counter_ns()
        try:
            result = subprocess.run(command, cwd=cwd, env=self.env, capture_output=True, timeout=self.timeout)
            out, err, status = result.stdout, result.stderr, result.returncode
        except subprocess.TimeoutExpired as error:
            out, err, status = error.stdout or b"", error.stderr or b"", None
            err += f"\nTimed out after {self.timeout} seconds\n".encode()
        except OSError as error:
            out, err, status = b"", str(error).encode(), None
        elapsed = (perf_counter_ns() - started) / 1e6
        log.with_suffix(".stdout").write_bytes(out)
        log.with_suffix(".stderr").write_bytes(err)
        peak_rss = None
        if wrapped and status == 0 and memory.is_file():
            value = memory.read_text(encoding="ascii").strip()
            if value.isdecimal() and int(value) > 0:
                peak_rss = int(value)
        return {
            "returnCode": status, "elapsedMs": elapsed, "peakRssKiB": peak_rss,
            "log": str(log),
        }, out.decode("utf-8", errors="replace"), err.decode("utf-8", errors="replace")


def checked(result):
    execution, out, err = result
    require(execution["returnCode"] == 0, f"Command failed. See {execution['log']}.*")
    return execution, out, err


def coverage(runner, artifact, directory):
    log = directory / "javap"
    result, out, _ = runner.run([java_tool("javap"), "-p", "-s", "-classpath", artifact, OWNER], log)
    if result["returnCode"] != 0:
        return {"status": "unavailable", "reason": f"Could not inspect the selected JVM owner. See {log}.*"}
    inspected = {}
    declaration = None
    for line in out.splitlines():
        if line.strip().startswith("descriptor:") and declaration is not None:
            name, is_native = declaration
            inspected[name + line.split(":", 1)[1].strip()] = is_native
            declaration = None
        elif match := re.search(r"^\s*(.*?)\b(\w+)\([^)]*\)(?:\s+throws [^;]+)?;\s*$", line):
            declaration = match[2], "native" in match[1].split()
    missing = set(METHODS) - inspected.keys()
    if missing:
        return {"status": "unavailable", "reason": "Selected fixture names or descriptors changed",
                "missingMethods": sorted(missing)}
    native = [method for method in METHODS if inspected[method]]
    return {"status": "measured", "requestedMethods": len(METHODS), "nativeMethods": native,
            "percent": 100 * len(native) / len(METHODS)}


def artifact_metrics(runner, artifact, lane, directory):
    report = {"bytes": artifact.stat().st_size, "sha256": digest(artifact)}
    if lane == "java":
        with zipfile.ZipFile(artifact) as archive:
            payload = [
                {"entry": entry.filename, "bytes": entry.file_size, "compressedBytes": entry.compress_size}
                for entry in archive.infolist() if entry.filename.endswith((".so", ".dll"))
            ]
        report.update(nativePayload=payload, nativePayloadBytes=sum(item["bytes"] for item in payload),
                      coverage=coverage(runner, artifact, directory))
    return report


def compile_case(case, source, selection, root, runner, attempts):
    directory = root / case["name"]
    directory.mkdir()
    artifact = directory / ("artifact.jar" if case["lane"] == "java" else "artifact" + SUFFIX)
    output = artifact.with_stem("output")
    paths = {"{input}": source, "{output}": output, "{work}": directory, "{selection}": selection}
    argv = [paths.get(arg, arg) for arg in case["build"]]
    ver, out, err = runner.run(case["version"], directory / "version", cwd=directory)
    case["toolVersion"] = {"completed": ver["returnCode"] == 0, "output": (out + err).strip(), "log": ver["log"]}
    results = []
    for number in range(1, attempts + 1):
        result, _, _ = runner.run(argv, directory / f"build-{number}", cwd=directory, rss=True)
        result["success"] = result["returnCode"] == 0 and output.is_file() and output.stat().st_size > 0
        if result["success"]:
            result["sha256"] = digest(output)
            output.replace(artifact)
        else:
            result["failure"] = ("command failed" if result["returnCode"] != 0
                                 else "command did not create a nonempty artifact")
            if output.is_file():
                output.unlink()
        results.append(result)
    passed = [item for item in results if item["success"]]
    same = len({item["sha256"] for item in passed}) == 1
    case["buildMetrics"] = {
        "attempts": results, "successPercent": 100 * len(passed) / len(results),
        "medianMs": median(item["elapsedMs"] for item in passed) if passed else None,
        "reproducibleBytes": same if len(passed) > 1 else None,
    }
    return artifact if passed else None


def read_samples(out, err, phase, trials, log):
    require("WARNING in native method" not in out + err, f"JNI check warning. See {log}.*")
    names = WORKLOADS if phase == "steady" else (phase,)
    values = {name: [] for name in names}
    for line in out.splitlines():
        match = re.fullmatch(r"(\w+) (\d+) ([0-9a-f]{8})", line)
        require(match and match[1] in values, f"Invalid benchmark output. See {log}.*")
        values[match[1]].append((int(match[2]), match[3]))
    expected = trials if phase == "steady" else 1
    require(all(len(samples) == expected and len({value for _, value in samples}) == 1
                for samples in values.values()), f"Missing samples or inconsistent checksum. See {log}.*")
    checksums = {name: samples[0][1] for name, samples in values.items()}
    if phase == "steady":
        require(checksums["per_call"] == checksums["batched_32"], f"Batched recurrence differs. See {log}.*")
    return checksums, {name: [ns for ns, _ in samples] for name, samples in values.items()}


def run_lane(lane, artifacts, root, runner, args, errors):
    rng = random.Random(0)
    reference = f"{lane}-original"
    checks = {name: {} for name in artifacts}
    timings = {name: {"startup": [], "steady": []} for name in artifacts}
    failed = set()
    sweeps = [("probe", seed, 1) for seed in args.seed]
    sweeps += [("startup", args.seed[0], args.startup_runs), ("steady", args.seed[0], args.forks)]
    for phase, seed, repeats in sweeps:
        for number in range(1, repeats + 1):
            order = list(artifacts)
            rng.shuffle(order)
            for name in order:
                if name in failed:
                    continue
                artifact = artifacts[name]
                argv = [str(artifact)]
                if lane == "java":
                    argv = [java_tool("java"), "--enable-native-access=ALL-UNNAMED"]
                    if phase == "probe":
                        argv += ["-Xverify:all", "-Xcheck:jni"]
                    argv += ["-jar", str(artifact)]
                argv += [phase, seed]
                if phase == "steady":
                    argv += [args.iterations, args.warmups, args.trials]
                log = root / name / f"{phase}-{seed}-{number}"
                try:
                    result, out, err = checked(runner.run(argv, log, rss=phase != "probe"))
                    checksums, times = read_samples(out, err, phase, args.trials, log)
                    checks[name][log.name] = checksums
                    if phase != "probe":
                        timings[name][phase].append({
                            "processMs": result["elapsedMs"], "peakRssKiB": result["peakRssKiB"],
                            "samplesNs": times,
                        })
                except ValueError as error:
                    errors.append(f"{name}: {error}")
                    failed.add(name)
    reports = {}
    for name, digest_by_run in checks.items():
        if name in failed or reference in failed or reference not in checks:
            reports[name] = {"semanticMatch": None}
            continue
        different = [run for run, values in checks[reference].items() if digest_by_run.get(run) != values]
        if different:
            errors.append(f"{name}: checksum mismatch for {', '.join(different)}")
            reports[name] = {"semanticMatch": False, "checksums": digest_by_run}
            continue
        startup, steady = timings[name]["startup"], timings[name]["steady"]
        rss = [sample["peakRssKiB"] for sample in steady]
        reports[name] = {
            "semanticMatch": True, "checksums": digest_by_run,
            "startup": {"medianProcessMs": median(sample["processMs"] for sample in startup),
                        "medianFirstCallMs": median(sample["samplesNs"]["startup"][0] for sample in startup) / 1e6,
                        "samples": startup},
            "steady": {"medianNsPerUpdate": {
                workload: median(median(sample["samplesNs"][workload]) for sample in steady) / args.iterations
                for workload in WORKLOADS
            }, "medianPeakRssKiB": median(rss) if all(sample is not None for sample in rss) else None,
                       "forks": steady},
        }
    return reports


def compare(case, reference):
    result = {}
    stats = case.get("artifact", {})
    base = reference.get("artifact", {})

    def overhead(value, denominator):
        return (value / denominator - 1) * 100 if value is not None and denominator else None

    result["artifactGrowthPercent"] = overhead(stats.get("bytes"), base.get("bytes"))
    if case.get("semanticMatch") and reference.get("semanticMatch"):
        result["startupIncreaseMs"] = case["startup"]["medianProcessMs"] - reference["startup"]["medianProcessMs"]
        measured, standard = case["steady"], reference["steady"]
        result["runtimeOverheadPercent"] = {
            workload: overhead(measured["medianNsPerUpdate"][workload], standard["medianNsPerUpdate"][workload])
            for workload in WORKLOADS
        }
        result["peakRssOverheadPercent"] = overhead(measured["medianPeakRssKiB"], standard["medianPeakRssKiB"])
    return result


def budget_errors(case, comparison):
    build = case.get("buildMetrics", {})
    artifact = case.get("artifact", {})
    measured = {
        "maxBuildMs": build.get("medianMs"), "minBuildSuccessPercent": build.get("successPercent"),
        "minNativeCoveragePercent": artifact.get("coverage", {}).get("percent"),
        "maxArtifactGrowthPercent": comparison.get("artifactGrowthPercent"),
        "maxStartupIncreaseMs": comparison.get("startupIncreaseMs"),
        "maxRuntimeOverheadPercent": comparison.get("runtimeOverheadPercent"),
        "maxPeakRssOverheadPercent": comparison.get("peakRssOverheadPercent"),
    }
    violations = []
    for limit, threshold in case.get("limits", {}).items():
        value = measured[limit]
        readings = value.items() if isinstance(value, dict) else [(limit, value)]
        for metric, reading in readings:
            if reading is None:
                violations.append(f"{case['name']} {metric}: required measurement unavailable")
            elif (reading < threshold and limit.startswith("min")
                  or reading > threshold and limit.startswith("max")):
                violations.append(f"{case['name']} {metric}: {reading:.3f} violates {limit}={threshold:g}")
    return violations


def arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="new directory for report and logs")
    parser.add_argument("--adapters", type=Path, help="JSON comparison adapters")
    parser.add_argument("--native-limit", action="append", default=[], metavar="METRIC=VALUE",
                        help="budget for the built-in native-obfuscator case; repeatable")
    parser.add_argument("--distribution", type=Path, default=HERE.parent / "build/install/native-obfuscator")
    parser.add_argument("--zig", type=Path)
    parser.add_argument("--include-c", action="store_true", help="also compile and run the C baseline")
    parser.add_argument("--cc", default=os.environ.get("CC", "cc"))
    parser.add_argument("--gnu-time", help="GNU time executable for peak RSS (autodetected on Linux)")
    parser.add_argument("--seed", type=int, action="append", help="repeatable; defaults to 637 and 2309737967")
    parser.add_argument("--build-runs", type=int, default=3)
    parser.add_argument("--startup-runs", type=int, default=7)
    parser.add_argument("--forks", type=int, default=3)
    parser.add_argument("--warmups", type=int, default=5)
    parser.add_argument("--trials", type=int, default=9)
    parser.add_argument("--iterations", type=int, default=524288)
    parser.add_argument("--timeout", type=int, default=180, help="seconds per command")
    args = parser.parse_args()
    counts = (args.build_runs, args.startup_runs, args.forks, args.warmups, args.trials, args.timeout)
    if any(not 0 < count < 1 << 31 for count in counts):
        parser.error("measurement counts and timeout must be positive signed 32-bit integers")
    if not BATCH <= args.iterations < 1 << 31 or args.iterations % BATCH:
        parser.error(f"iterations must be positive, signed 32-bit and divisible by {BATCH}")
    args.seed = list(dict.fromkeys(args.seed or [637, 2309737967]))
    if any(not 0 <= seed < 1 << 32 for seed in args.seed):
        parser.error("seeds must be unsigned 32-bit integers")
    limits = {}
    for rule in args.native_limit:
        key, _, value = rule.partition("=")
        try:
            require(key not in limits, f"Repeated budget {key}")
            limits[key] = float(value)
            validate_limits("native-obfuscator", limits)
        except ValueError as error:
            parser.error(f"Invalid --native-limit {rule}: {error}")
    args.native_limit = limits
    return args


def main(args):
    external = load_adapters(args.adapters) if args.adapters else []
    root = args.output.resolve()
    root.mkdir(parents=True)
    cc = shutil.which(args.cc)
    if cc:
        args.cc = str(Path(cc).absolute())
    env = {**os.environ, "LC_ALL": "C", "CC": args.cc}
    time_executable = args.gnu_time or ("/usr/bin/time" if platform.system() == "Linux"
                                        and Path("/usr/bin/time").is_file() else None)
    runner = Runner(env, args.timeout, time_executable)
    if time_executable:
        _, version, _ = checked(runner.run([time_executable, "--version"], root / "gnu-time"))
        require("gnu time" in version.lower(), f"{time_executable} is not GNU time")
    java_root = root / "java-original"
    java_root.mkdir()
    classes = java_root / "classes"
    classes.mkdir()
    source = HERE / "analysis-workload.java"
    artifact = java_root / "artifact.jar"
    javac, _, _ = checked(runner.run([java_tool("javac"), "--release", "21", "-encoding", "UTF-8",
                                     "-d", classes, source], java_root / "javac"))
    jar, _, _ = checked(runner.run([java_tool("jar"), "--create", "--date=2020-01-01T00:00:00Z", "--file",
                                   artifact, "--main-class", "AnalysisWorkload", "-C", classes, "."], java_root / "jar"))
    _, jdk_out, jdk_err = checked(runner.run([java_tool("java"), "-version"], java_root / "jdk"))
    selection = root / "selected-methods.txt"
    selection.write_text("".join(f"{OWNER}#{method}\n" for method in METHODS), encoding="utf-8")
    libs = args.distribution.resolve() / "lib"
    tool = [java_tool("java"), "-cp", str(libs / "*"), "dev.nativeobfuscator.Main"]
    command = [*tool, "protect", "--input", "{input}", "--output", "{output}",
               "--unsupported", "retain", "--padding", "off"]
    for method in METHODS:
        command += ["--include", f"{OWNER}#{method}"]
    if args.zig:
        command += ["--zig", str(args.zig.resolve())]
    compiler = [str(args.zig.resolve()), "version"] if args.zig else [args.cc, "--version"]
    _, compiler_out, compiler_err = checked(runner.run(compiler, root / "native-compiler"))
    c_source = HERE / "comparison-workload.c"
    cases = [
        {"name": "native-obfuscator", "lane": "java", "build": command, "version": [*tool, "--help"],
         "limits": args.native_limit},
    ]
    if args.include_c or any(case["lane"] == "c" for case in external):
        cases.append({"name": "c-original", "lane": "c",
                      "build": [args.cc, "-std=c11", "-O2", "{input}", "-o", "{output}"],
                      "version": [args.cc, "--version"]})
    cases.extend(external)
    sources = {"java": artifact, "c": c_source}
    runtimes = {"java": {"java-original": artifact}, "c": {}}
    report_cases = {"java-original": {
        "name": "java-original", "lane": "java", "fixtureBuildMs": javac["elapsedMs"] + jar["elapsedMs"],
        "artifact": artifact_metrics(runner, artifact, "java", java_root),
    }}
    report_cases.update((case["name"], case) for case in cases)
    errors = []
    for case in cases:
        name, lane = case["name"], case["lane"]
        package = compile_case(case, sources[lane], selection, root, runner, args.build_runs)
        if not case["toolVersion"]["completed"]:
            errors.append(f"{name}: tool version command failed. See {case['toolVersion']['log']}.*")
        if case["buildMetrics"]["successPercent"] < 100 and "minBuildSuccessPercent" not in case.get("limits", {}):
            errors.append(f"{name}: at least one build failed. See {root / name}/build-*")
        if package is None:
            errors.append(f"{name}: no runnable artifact")
            continue
        try:
            case["artifact"] = artifact_metrics(runner, package, lane, root / name)
            runtimes[lane][name] = package
        except (OSError, zipfile.BadZipFile) as error:
            errors.append(f"{name}: invalid build artifact: {error}")
    for lane, packages in runtimes.items():
        for name, result in run_lane(lane, packages, root, runner, args, errors).items():
            report_cases[name].update(result)
        if packages and f"{lane}-original" not in packages:
            errors.append(f"{lane}: original executable unavailable for semantic comparison")
    c_baseline = report_cases.get("c-original", {})
    java_baseline = report_cases["java-original"]
    common_checksums = {}
    if c_baseline.get("semanticMatch") and java_baseline.get("semanticMatch"):
        common_checksums = {key: value for key, value in c_baseline["checksums"].items()
                            if not key.startswith("probe-")}
        if any(java_baseline["checksums"][key] != value for key, value in common_checksums.items()):
            errors.append("C and Java baselines disagree on the common startup or steady workload")
    violations = []
    for case in cases:
        reference = case.get("relativeTo", f"{case['lane']}-original")
        case["relativeTo"] = reference
        if reference != case["name"]:
            case["comparison"] = compare(case, report_cases[reference])
        violations += budget_errors(case, case.get("comparison", {}))
    report = {
        "schema": 1, "status": "failed" if errors or violations else "passed",
        "host": {"platform": platform.platform(), "architecture": platform.machine(),
                 "pythonVersion": platform.python_version(), "javaVersion": (jdk_out + jdk_err).strip(),
                 "nativeCompiler": (compiler_out + compiler_err).strip()},
        "sourcesSha256": {path.name: digest(path) for path in (source, c_source, Path(__file__))},
        "distributionJarSha256": digest(libs / "native-obfuscator.jar") if (libs / "native-obfuscator.jar").is_file() else None,
        "settings": {"seeds": args.seed, "buildRuns": args.build_runs, "startupRuns": args.startup_runs,
                     "steadyForks": args.forks, "warmups": args.warmups, "trials": args.trials,
                     "updatesPerTrial": args.iterations, "gnuTime": time_executable},
        "errors": errors, "budgetViolations": violations, "commonLaneChecksums": common_checksums,
        "cases": report_cases,
    }
    summary = root / "summary.json"
    summary.write_text(json.dumps(report, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    print(f"Benchmark {report['status']}. Report at {summary}")
    return 1 if errors or violations else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main(arguments()))
    except (ValueError, OSError) as error:
        raise SystemExit(str(error)) from error
