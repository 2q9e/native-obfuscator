#!/usr/bin/env python3
"""Run the JNI coverage differential locally; accepts a distribution directory and protect flags."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from zipfile import ZipFile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("distribution", type=Path)
parser.add_argument("--work", type=Path, help="retain local artifacts and a JSON result here")
args, flags = parser.parse_known_args()
root = Path(__file__).resolve().parent
java_home = os.environ.get("JAVA_HOME")
def executable(name):
    return str(Path(java_home) / "bin" / name) if java_home else name

def run(command, expected=0):
    result = subprocess.run([str(x) for x in command], text=True, capture_output=True)
    if result.returncode != expected:
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
selection = ["--input", original, "--include", "Coverage#cov*", "--include", "RawOps#cov*",
             "--include", "Coverage#reject*", "--include", "RawOps#reject*", "--unsupported", "retain", "--padding", "off"]
report = run(tool + ["protect", "--output", protected] + selection + flags)
(work / "report.jsonl").write_text(report.stdout)
(work / "build.log").write_text(report.stderr)
decisions = [json.loads(line) for line in report.stdout.splitlines()]
raw_methods = {"covRawZ", "covRawB", "covRawC", "covRawS", "covSpecial", "covReferenceCapacity",
               "covLinkageRaw", "covLinkageMissing", "covExternalNull", "covExternalProtected", "covExternalMissingField",
               "covCastMissing", "covAllocateMissingArray", "covPlainConcat"}
expected = raw_methods | set(re.findall(r"\b(cov\w+)\(", (root / "Coverage.java").read_text()))
utilities = {d["method"] for d in decisions if d.get("result") == "analysis-utility"}
selected = {d["method"].split("#")[1].split("(")[0] for d in decisions
            if d.get("result") == "selected" and d["method"] not in utilities}
assert selected == expected, {"unexpected": sorted(selected - expected), "missing": sorted(expected - selected)}
declarations = run([executable("javap"), "-p", "-classpath", protected, "Coverage", "RawOps"]).stdout
native_declarations = set(re.findall(r"\bnative\b[^;]*?\b(cov\w+)\(", declarations))
assert native_declarations == expected, declarations
(work / "declarations.txt").write_text(declarations)
retained = {d["method"].split("#")[1].split("(")[0]: d["reason"] for d in decisions if d.get("result") == "retained"}
assert set(retained) == {"rejectAllocation", "rejectRawCall", "rejectDirectHierarchy", "rejectUnknownHierarchy",
                         "rejectLambdaMarkers"}, retained
assert "bytecode verification failed" in retained["rejectDirectHierarchy"]
assert "bytecode verification failed" in retained["rejectUnknownHierarchy"]
assert "object allocation" in retained["rejectAllocation"]
assert "not proven to fit JNI descriptor B" in retained["rejectRawCall"]
assert "dynamic call-site linkage" in retained["rejectLambdaMarkers"]
verified = run(tool + ["verify", "--staged", protected] + selection + flags)
(work / "verify.jsonl").write_text(verified.stdout)
verification = json.loads(verified.stdout.splitlines()[-1])
assert verification["verification"]["structurallyValid"], verification
# A versioned superclass may supply a different hierarchy on another JVM.
versioned = work / "versioned.jar"
versioned_base = "Coverage$DefaultDispatch.class"
with ZipFile(original) as source, ZipFile(versioned, "w") as destination:
    for name in source.namelist():
        content = source.read(name)
        if name == "META-INF/MANIFEST.MF":
            content = content.rstrip(b"\r\n") + b"\r\nMulti-Release: true\r\n\r\n"
        destination.writestr(name, content)
    destination.writestr("META-INF/versions/25/" + versioned_base, source.read(versioned_base))
versioned_report = run(tool + ["analyze", "--input", versioned, "--include", "Coverage#covHierarchy*",
                               "--unsupported", "retain", "--padding", "off"])
versioned_decisions = [json.loads(line) for line in versioned_report.stdout.splitlines()]
assert {d["method"].split("#")[1].split("(")[0]: d["result"] for d in versioned_decisions} == {
    "covHierarchyReceiver": "eligible", "covHierarchyInterface": "retained", "covHierarchyArguments": "retained"
}, versioned_decisions

dependency = work / "hierarchy-library.jar"
conflicting = work / "hierarchy-conflict.jar"
split_input = work / "hierarchy-input.jar"
split_protected = work / "hierarchy-protected.jar"
with ZipFile(original) as source, ZipFile(split_input, "w") as destination, ZipFile(dependency, "w") as library:
    for name in source.namelist():
        content = source.read(name)
        if name == versioned_base:
            library.writestr(name, content)
            continue
        if name == "META-INF/MANIFEST.MF":
            content = content.rstrip(b"\r\n") + b"\r\nClass-Path: hierarchy-library.jar\r\n\r\n"
        destination.writestr(name, content)
with ZipFile(conflicting, "w") as library:
    library.writestr(versioned_base, (work / "conflicting-dispatch.class").read_bytes())
split_options = ["--input", split_input, "--include", "Coverage#covHierarchy*", "--unsupported", "retain", "--padding", "off"]
unresolved = run(tool + ["analyze"] + split_options)
ambiguous = run(tool + ["analyze"] + split_options + ["--hierarchy-jar", dependency, "--hierarchy-jar", conflicting])
assert json.loads(unresolved.stdout.splitlines()[0])["result"] == "eligible", unresolved.stdout
assert sum(json.loads(line)["result"] == "retained" for line in unresolved.stdout.splitlines()) == 2, unresolved.stdout
assert ambiguous.stdout == unresolved.stdout, ambiguous.stdout
dependency_config = work / "hierarchy.json"
dependency_config.write_text(json.dumps({
    "input": str(split_input), "include": ["Coverage#covHierarchy*"], "hierarchyJar": [str(dependency)], "padding": "off"
}))
resolved = run(tool + ["analyze", "--config", dependency_config])
assert [json.loads(line)["result"] for line in resolved.stdout.splitlines()] == ["eligible"] * 3, resolved.stdout
split_linkage = ["--hierarchy-jar", dependency]
run(tool + ["harden", "--output", split_protected] + split_options + split_linkage + flags)
run(tool + ["verify", "--staged", split_protected] + split_options + split_linkage + flags)
run(tool + ["verify", "--staged", split_protected] + split_options + flags, expected=1)
with ZipFile(split_protected) as archive:
    assert versioned_base not in archive.namelist(), "Hierarchy library was copied into protected JAR"
split_result = run([executable("java"), "-Xverify:all", "-Xcheck:jni", "--enable-native-access=ALL-UNNAMED",
                    "-jar", split_protected])

original_result = run([executable("java"), "-Xverify:all", "-jar", original])
assert original_result.stdout == split_result.stdout, split_result.stdout
assert "WARNING in native method" not in split_result.stderr, split_result.stderr
protected_result = run([executable("java"), "-Xverify:all", "-Xcheck:jni", "--enable-native-access=ALL-UNNAMED", "-jar", protected])
(work / "original.txt").write_text(original_result.stdout)
(work / "protected.txt").write_text(protected_result.stdout)
(work / "execution.log").write_text(protected_result.stderr)
assert protected_result.stdout == original_result.stdout, "Differential mismatch; compare original.txt and protected.txt"
assert "WARNING in native method" not in protected_result.stderr, protected_result.stderr
assert "WARNING in native method" not in protected_result.stdout, protected_result.stdout
summary = {"malformedOperandChecks": 11, "randomizedNativeCalls": 20000, "selectedMethods": len(selected), "retainedMethods": retained, "outcomesCompared": len(original_result.stdout.splitlines()), "result": "pass", "compilerArguments": flags}
(work / "result.json").write_text(json.dumps(summary, indent=2) + "\n")
print(json.dumps(summary, indent=2))
if managed:
    managed.cleanup()
