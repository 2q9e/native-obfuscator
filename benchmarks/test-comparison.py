#!/usr/bin/env python3
"""Exercise the comparison runner with compiled and deliberately failing adapters."""

import argparse
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

from compare import budget_errors, compare, kernel_encodings


HERE = Path(__file__).resolve().parent


def check_disassembly():
    measurements = kernel_encodings(
        "00000000400818 <workload_step>:\n"
        "  400818: d65f03c0     \tret\n"
        "0000000040081c <workload_mix>:\n"
        "  40081c: 89 f8        \tmovl %edi, %eax\n"
        "  40081e: c3           \tretq\n"
        "0000000040081f <other>:\n"
        "  40081f: c3           \tretq\n"
    )
    assert set(measurements) == {"workload_step", "workload_mix"}
    assert measurements["workload_step"]["encodedBytes"] == 4
    assert measurements["workload_mix"]["encodedBytes"] == 3
    assert measurements["workload_mix"]["instructions"] == 2
    assert measurements["workload_step"]["encodingSha256"] != measurements["workload_mix"]["encodingSha256"]


def check_workload_budgets():
    def semantic_case(step, call, batch):
        return {
            "semanticMatch": True, "startup": {"medianProcessMs": 0},
            "steady": {"medianNsPerUpdate": {"step": step, "per_call": call, "batched_32": batch},
                       "medianPeakRssKiB": None},
        }
    measurement = compare(semantic_case(7.5, 7.5, 1.3), semantic_case(0.5, 1.5, 1.3))
    limits = {"maxStepOverheadNs": 12, "maxPerCallOverheadPercent": 700, "maxBatchOverheadPercent": 100}
    assert not budget_errors({"name": "fixture", "limits": limits}, measurement)
    for metric, too_low in (("maxStepOverheadNs", 6), ("maxPerCallOverheadPercent", 399)):
        errors = budget_errors({"name": "fixture", "limits": {metric: too_low}}, measurement)
        assert len(errors) == 1 and f"violates {metric}" in errors[0], errors
    errors = budget_errors({"name": "fixture", "limits": limits}, {})
    assert len(errors) == 3 and all("required measurement unavailable" in error for error in errors), errors


def run(args, root, name, entries, *options):
    manifest = root / (name + ".json")
    manifest.write_text(json.dumps(entries), encoding="utf-8")
    output = root / name
    command = [
        sys.executable, str(HERE / "compare.py"), "--distribution", str(args.distribution),
        "--cc", args.cc, "--adapters", str(manifest), "--output", str(output), "--include-c",
        "--build-runs", "2", "--startup-runs", "1", "--forks", "1", "--warmups", "2",
        "--trials", "3", "--iterations", "65536", *options,
    ]
    if args.zig:
        command += ["--zig", str(args.zig)]
    result = subprocess.run(command, check=False, capture_output=True, text=True)
    assert (output / "summary.json").is_file(), result.stderr
    return result, json.loads((output / "summary.json").read_text(encoding="utf-8"))


def main():
    check_workload_budgets()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--distribution", type=Path, default=HERE.parent / "build/install/native-obfuscator")
    parser.add_argument("--cc", default="cc")
    parser.add_argument("--zig", type=Path)
    args = parser.parse_args()
    check_disassembly()
    cc = shutil.which(args.cc) or args.cc
    clone = {
        "name": "jar-control", "lane": "java", "relativeTo": "native-obfuscator",
        "version": [sys.executable, "--version"],
        "build": [sys.executable, "-c", "import shutil, sys; shutil.copyfile(*sys.argv[1:])", "{input}", "{output}"],
        "limits": {"minBuildSuccessPercent": 100},
    }
    c_clone = {
        "name": "c-control", "lane": "c", "version": [cc, "--version"],
        "build": [cc, "-std=c11", "-O2", "{input}", "-o", "{output}"],
        "limits": {"maxArtifactGrowthPercent": 0},
    }
    with tempfile.TemporaryDirectory(prefix="benchmark-regression-") as tmp:
        root = Path(tmp)
        result, report = run(args, root, "control", [clone, c_clone],
                             "--native-limit", "minNativeCoveragePercent=100")
        assert result.returncode == 0 and report["status"] == "passed", report
        assert report["commonLaneChecksums"], report
        cases = report["cases"]
        assert all(case["semanticMatch"] for case in cases.values()), report
        assert cases["jar-control"]["buildMetrics"]["reproducibleBytes"], report
        assert cases["jar-control"]["artifact"]["coverage"]["percent"] == 0, report
        assert cases["native-obfuscator"]["artifact"]["coverage"]["percent"] > 0, report

        mutant = {
            "name": "c-mutant", "lane": "c", "version": [cc, "--version"],
            "build": [
                sys.executable, "-c",
                "from pathlib import Path; import subprocess, sys; "
                "source = Path('mutant.c'); "
                "source.write_text(Path(sys.argv[1]).read_text().replace('0x13271953', '0x13271952')); "
                "subprocess.run([sys.argv[3], '-std=c11', '-O2', str(source), '-o', sys.argv[2]], check=True)",
                "{input}", "{output}", cc,
            ],
        }
        fail = {**clone, "name": "empty-build", "build": [sys.executable, "-c", "pass", "{input}", "{output}"]}
        coverage_limit = {**clone, "limits": {"minNativeCoveragePercent": 1}}
        result, report = run(args, root, "failures", [mutant, fail, coverage_limit],
                             "--native-limit", "maxArtifactGrowthPercent=0")
        assert result.returncode == 1 and report["status"] == "failed", report
        assert report["cases"]["c-mutant"]["semanticMatch"] is False, report
        assert "steady" not in report["cases"]["c-mutant"], report
        assert report["cases"]["empty-build"]["buildMetrics"]["successPercent"] == 0, report
        assert any("checksum mismatch" in error for error in report["errors"]), report
        assert any("minNativeCoveragePercent=1" in error for error in report["budgetViolations"]), report
        assert any(error.startswith("native-obfuscator") for error in report["budgetViolations"]), report
    print("Comparison benchmark accepted equivalent builds and rejected wrong results, missing artifacts and budget violations.")


if __name__ == "__main__":
    main()
