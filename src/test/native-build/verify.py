#!/usr/bin/env python3
"""Exercise native compilation limits and preset scoping through the installed CLI."""

import argparse
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parents[3]


def run(argv, expected=0, env=None):
    result = subprocess.run(argv, capture_output=True, text=True, timeout=90, env=env)
    if result.returncode != expected:
        raise AssertionError(
            f"Expected exit {expected}, got {result.returncode} for {argv}\n{result.stdout}\n{result.stderr}"
        )
    return result


def limits(tool, common, work, original, java):
    output = work / "budget.jar"
    placeholder = b"prior artifact"
    output.write_bytes(placeholder)
    for action in ("protect", "harden"):
        result = run([tool, action, *common, "--output", output, "--max-native-bytes", "1"], expected=1)
        assert "Native library size limit exceeded" in result.stderr and "(limit 1)" in result.stderr
        assert output.read_bytes() == placeholder and not list(work.glob(".native-obfuscator-stage-*"))

    wait_compiler = work / "wait-compiler"
    wait_compiler.write_text("#!/bin/sh\nexec sleep 30\n", encoding="utf-8")
    wait_compiler.chmod(0o700)
    result = run([tool, "protect", *common, "--output", output, "--native-timeout-seconds", "1"],
                 expected=1, env={**os.environ, "CC": str(wait_compiler)})
    assert "timed out after 1 second" in result.stderr and output.read_bytes() == placeholder
    result = run([tool, "protect", *common, "--output", output, "--native-timeout-seconds", "0"], expected=2)
    assert "--native-timeout-seconds must be a positive integer" in result.stderr

    command = [tool, "protect", *common, "--output", output]
    run(command)
    with zipfile.ZipFile(output) as archive:
        library, = (item for item in archive.infolist() if item.filename.endswith(".so"))
    run([*command, "--max-native-bytes", str(library.file_size)])
    assert execute(java, output) == original
    first = output.read_bytes()
    result = run([*command, "--report-native-sizes"])
    rows = [json.loads(line) for line in result.stdout.splitlines()]
    sizes = [row["nativeBytes"] for row in rows if row["result"] == "native-size"]
    assert len(sizes) == 1 and sizes[0] > 0, rows
    with zipfile.ZipFile(output) as with_sizes:
        with zipfile.ZipFile(io.BytesIO(first)) as without_sizes:
            assert with_sizes.read(library.filename) == without_sizes.read(library.filename)


def execute(java, jar):
    result = run([java, "-Xverify:all", "-Xcheck:jni", "--enable-native-access=ALL-UNNAMED", "-jar", jar])
    assert not result.stderr, result.stderr
    return result.stdout


def scoped(tool, original_jar, original, java, work, options):
    output = work / "scoped.jar"
    config = work / "scoped.json"
    values = {
        "input": str(original_jar),
        "output": str(output),
        "include": ["Arithmetic#calcInt(III)I", "Arithmetic#calcLong*"],
        "llvm-include": ["Arithmetic#calcInt(III)I", "Arithmetic#calcLong*"],
        "llvm-exclude": ["Arithmetic#calcLong*"],
        "padding": "off",
        **options,
    }
    config.write_text(json.dumps(values), encoding="utf-8")
    command = [tool, "protect", "--config", config]
    result = run(command)
    function_line = re.compile(r"native-obfuscator-function: pass=native-encode .* transformed=(\d+) .* method=(\S+)")
    reports = [(method, int(sites)) for sites, method in function_line.findall(result.stderr)]
    assert ("Arithmetic#calcInt(III)I",) == tuple(method for method, sites in reports if sites > 0), reports
    assert all(line["result"] == "selected" for line in map(json.loads, result.stdout.splitlines()))
    assert any(line["method"].startswith("Arithmetic#calcLong") for line in map(json.loads, result.stdout.splitlines()))
    assert execute(java, output) == original
    first = output.read_bytes()
    run(command)
    assert first == output.read_bytes(), "Identical LLVM builds produced different JAR bytes"

    values["llvm-exclude"] = ["Arithmetic#*"]
    config.write_text(json.dumps(values), encoding="utf-8")
    result = run(command)
    assert not any(int(sites) for sites, _ in function_line.findall(result.stderr))
    assert "LLVM pipeline left final executable sections unchanged" in result.stderr
    assert execute(java, output) == original

    values.update({"analysisResistance": "balanced", "analysisHelperMethods": 0, "analysisDecoyMethods": 0})
    config.write_text(json.dumps(values), encoding="utf-8")
    result = run([tool, "harden", "--config", config])
    records = [json.loads(line) for line in result.stdout.splitlines()]
    target, = next(record["targets"] for record in records if "analysisResistance" in record)
    assert not target["llvmComparison"]["executableChanged"], target
    passes = target["llvmPasses"]
    assert passes and all(entry["transformed"] == 0 and entry["rejections"] for entry in passes), passes
    assert records[-1]["verification"]["structurallyValid"], records[-1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tool", type=Path)
    parser.add_argument("--clang")
    parser.add_argument("--llvm-opt")
    parser.add_argument("--llvm-plugin", type=Path)
    args = parser.parse_args()
    llvm = (args.clang, args.llvm_opt, args.llvm_plugin)
    if any(llvm) and not all(llvm):
        parser.error("Specify --clang, --llvm-opt and --llvm-plugin together")
    java_bin = Path(os.environ["JAVA_HOME"]) / "bin"
    tool = args.tool.resolve()
    with tempfile.TemporaryDirectory(prefix="native-build-check-") as directory:
        work = Path(directory)
        classes = work / "classes"
        classes.mkdir()
        source = ROOT / "src/test/fixtures/Arithmetic.java"
        run([java_bin / "javac", "--release", "21", "-d", classes, source])
        jar = work / "original.jar"
        run([java_bin / "jar", "--create", "--file", jar, "--main-class", "Arithmetic", "-C", classes, "."])
        original = execute(java_bin / "java", jar)
        common = ["--input", jar, "--include", "Arithmetic#calcInt(III)I", "--padding", "off"]
        limits(tool, common, work, original, java_bin / "java")
        if all(llvm):
            scoped(tool, jar, original, java_bin / "java", work, {
                "clang": args.clang, "llvm-opt": args.llvm_opt, "llvm-plugin": str(args.llvm_plugin.resolve())
            })
    print("Native build limits passed" + (" with deterministic LLVM scoping" if all(llvm) else ""))


if __name__ == "__main__":
    main()
