#!/usr/bin/env python3
"""Run the JNI coverage differential locally; accepts a distribution directory and protect flags."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("distribution", type=Path)
parser.add_argument("--work", type=Path, help="retain local artifacts and a JSON result here")
args, flags = parser.parse_known_args()
root = Path(__file__).resolve().parent
java_home = os.environ.get("JAVA_HOME")
def executable(name):
    return str(Path(java_home) / "bin" / name) if java_home else name

def run(command):
    result = subprocess.run([str(x) for x in command], text=True, capture_output=True)
    if result.returncode:
        raise AssertionError(f"Command failed ({result.returncode}): {command}\n{result.stdout}\n{result.stderr}")
    return result

managed = tempfile.TemporaryDirectory(prefix="native-coverage-") if args.work is None else None
work = Path(managed.name) if managed else args.work.resolve()
work.mkdir(parents=True, exist_ok=True)
classes = work / "classes"
classes.mkdir(exist_ok=True)
classpath = args.distribution.resolve() / "lib" / "*"
run([executable("javac"), "--release", "21", "-Xlint:all", "-Werror", "-cp", classpath, "-d", classes, root / "GenerateChecks.java"])
checks = run([executable("java"), "-cp", str(classes) + os.pathsep + str(classpath), "GenerateChecks", classes])
(work / "operand-checks.txt").write_text(checks.stdout)
(classes / "GenerateChecks.class").unlink()
run([executable("javac"), "--release", "21", "-Xlint:all", "-Werror", "-cp", classes, "-d", classes, root / "Coverage.java"])
original = work / "original.jar"
protected = work / "protected.jar"
run([executable("jar"), "--create", "--file", original, "--main-class", "Coverage", "-C", classes, "."])
tool = [executable("java"), "-cp", args.distribution.resolve() / "lib" / "*", "dev.nativeobfuscator.Main"]
report = run(tool + ["protect", "--input", original, "--output", protected, "--include", "Coverage#cov*", "--include", "RawOps#cov*", "--include", "Coverage#reject*", "--include", "RawOps#reject*", "--unsupported", "retain", "--padding", "off"] + flags)
(work / "report.jsonl").write_text(report.stdout)
(work / "build.log").write_text(report.stderr)
decisions = [json.loads(line) for line in report.stdout.splitlines()]
expected = {"covRawZ", "covRawB", "covRawC", "covRawS", "covSpecial", "covReferenceCapacity"} | set(re.findall(r"\b(cov\w+)\(", (root / "Coverage.java").read_text()))
selected = {d["method"].split("#")[1].split("(")[0] for d in decisions if d["result"] == "selected"}
assert selected == expected, (selected, expected, report.stdout)
declarations = run([executable("javap"), "-p", "-classpath", protected, "Coverage", "RawOps"]).stdout
native_declarations = set(re.findall(r"\bnative\b[^;]*?\b(cov\w+)\(", declarations))
assert native_declarations == expected, declarations
(work / "declarations.txt").write_text(declarations)
retained = {d["method"].split("#")[1].split("(")[0]: d["reason"] for d in decisions if d["result"] == "retained"}
assert set(retained) == {"rejectExternal", "rejectAllocation", "rejectHandler", "rejectReferenceLoop", "rejectVolatile", "rejectExternalField", "rejectRawCall"}, retained
assert "outside the declaring class" in retained["rejectExternal"]
assert "memory-model barriers" in retained["rejectVolatile"]
assert "exception handlers" in retained["rejectHandler"]
assert "local-reference lifetime" in retained["rejectReferenceLoop"]
assert "not proven to fit JNI descriptor B" in retained["rejectRawCall"]
original_result = run([executable("java"), "-Xverify:all", "-jar", original])
protected_result = run([executable("java"), "-Xverify:all", "-Xcheck:jni", "--enable-native-access=ALL-UNNAMED", "-jar", protected])
(work / "original.txt").write_text(original_result.stdout)
(work / "protected.txt").write_text(protected_result.stdout)
(work / "execution.log").write_text(protected_result.stderr)
assert protected_result.stdout == original_result.stdout, "Differential mismatch; compare original.txt and protected.txt"
assert "WARNING in native method" not in protected_result.stderr, protected_result.stderr
assert "WARNING in native method" not in protected_result.stdout, protected_result.stdout
summary = {"malformedOperandChecks": 8, "randomizedNativeCalls": 20000, "selectedMethods": len(selected), "retainedMethods": retained, "outcomesCompared": len(original_result.stdout.splitlines()), "result": "pass", "compilerArguments": flags}
(work / "result.json").write_text(json.dumps(summary, indent=2) + "\n")
print(json.dumps(summary, indent=2))
if managed:
    managed.cleanup()
