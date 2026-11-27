#!/usr/bin/env python3
"""Exercise the comparison runner with compiled and deliberately failing adapters."""

import argparse
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile


HERE = Path(__file__).resolve().parent


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
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--distribution", type=Path, default=HERE.parent / "build/install/native-obfuscator")
    parser.add_argument("--cc", default="cc")
    parser.add_argument("--zig", type=Path)
    args = parser.parse_args()
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
                             "--native-limit", "minNativeCoveragePercent=85")
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
                             "--native-limit", "minNativeCoveragePercent=100")
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
