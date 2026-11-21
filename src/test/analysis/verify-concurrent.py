#!/usr/bin/env python3
"""Compare concurrent class initialization in original and balanced JARs on the host."""

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parents[3]
BUILD = ROOT / "build"
OWNERS = ("ConcurrentLeft", "ConcurrentRight")
METHODS = ("calcStatic(I)I", "calcStatic(J)J", "calcInstance(I)I", "calcInstance(J)J")


def java_tool(name):
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        return Path(java_home) / "bin" / (name + (".exe" if os.name == "nt" else ""))
    return name


def run(command, work, label, timeout=210):
    command = [str(argument) for argument in command]
    result = subprocess.run(command, cwd=work, encoding="utf-8", errors="replace",
                            capture_output=True, timeout=timeout)
    (work / f"{label}.stdout.txt").write_text(result.stdout, encoding="utf-8")
    (work / f"{label}.stderr.txt").write_text(result.stderr, encoding="utf-8")
    if result.returncode != 0:
        raise AssertionError(f"{label} exited {result.returncode}: {command}\n{result.stdout}\n{result.stderr}")
    return result


def check_protection(report, jar, work):
    rows = [json.loads(line) for line in report.splitlines() if line.strip()]
    selected = sorted(row["method"] for row in rows if row.get("result") == "selected")
    expected = sorted(f"{owner}#{method}" for owner in OWNERS for method in METHODS)
    generated = {row["method"] for row in rows if row.get("result") == "analysis-utility"}
    selected = [method for method in selected if method not in generated]
    if selected != expected:
        raise AssertionError(f"Selected methods differ from the expected overloads: {selected}")

    declarations = run([java_tool("javap"), "-p", "-classpath", jar, *OWNERS], work, "declarations").stdout
    actual = sorted(re.findall(r"\bnative (int|long) (calcStatic|calcInstance)\((int|long)\);", declarations))
    expected = sorted((kind, method, kind) for _ in OWNERS
                      for kind in ("int", "long") for method in ("calcStatic", "calcInstance"))
    if actual != expected:
        raise AssertionError(f"Protected classes lack the expected native overloads:\n{declarations}")

    with zipfile.ZipFile(jar) as archive:
        names = archive.namelist()
    manifest = "analysis/manifest.bin"
    manifests = [name for name in names
                 if name.startswith("META-INF/native-obfuscator/") and name.endswith("/" + manifest)]
    if len(manifests) != 1:
        raise AssertionError(f"Expected one balanced runtime manifest: {manifests}")
    registration_root = manifests[0].removesuffix(manifest) + "registration/"
    registrations = sorted(name for name in names if name.startswith(registration_root) and name.endswith(".bin"))
    expected_registrations = [registration_root + f"{index}.bin" for index in range(len(OWNERS) + len(generated))]
    if registrations != expected_registrations:
        raise AssertionError(f"Expected application and utility registrations in one runtime: {registrations}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("distribution", nargs="?", type=Path, default=BUILD / "install" / "native-obfuscator")
    parser.add_argument("--zig", type=Path, help="path to Zig for the host native compiler")
    parser.add_argument("--rounds", type=int, default=8, help="pairs of original and protected JVM runs")
    args = parser.parse_args()
    if args.rounds < 1:
        parser.error("--rounds must be positive")
    libraries = args.distribution.resolve() / "lib"
    if not (libraries / "native-obfuscator.jar").is_file():
        parser.error("Build installDist or pass an installed distribution directory")

    zig = args.zig
    if zig is None and os.name == "nt":
        on_path = shutil.which("zig")
        zig = Path(on_path) if on_path else ROOT / ".tooling" / "zig-x86_64-windows-0.15.2" / "zig.exe"
    if zig is not None and not zig.is_file():
        parser.error("Pass --zig with an existing Zig executable")
    compiler = ["--zig", zig.resolve()] if zig else []

    BUILD.mkdir(exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="analysis-concurrent-", dir=BUILD))
    print(f"Concurrent initialization artifacts at {work}", flush=True)
    classes = work / "classes"
    classes.mkdir()
    java_tmp = work / "tmp"
    java_tmp.mkdir()
    java = [java_tool("java"), f"-Djava.io.tmpdir={java_tmp}"]
    original = work / "original.jar"
    protected = work / "balanced.jar"
    run([java_tool("javac"), "--release", "21", "-encoding", "UTF-8", "-Xlint:all", "-Werror",
         "-d", classes, Path(__file__).with_name("ConcurrentInitialization.java").resolve()], work, "javac")
    run([java_tool("jar"), "--create", "--file", original, "--main-class", "ConcurrentInitialization",
         "-C", classes, "."], work, "jar")

    report = run([*java, "-cp", libraries / "*", "dev.nativeobfuscator.Main", "protect", "--input", original,
                  "--output", protected, "--include", "ConcurrentLeft#calc*", "--include", "ConcurrentRight#calc*",
                  "--padding", "off", "--analysis-resistance", "balanced", "--analysis-seed", "42", *compiler],
                 work, "protect")
    check_protection(report.stdout, protected, work)

    verify = [*java, "-Xverify:all", "-Xcheck:jni", "--enable-native-access=ALL-UNNAMED", "-jar"]
    for number in range(1, args.rounds + 1):
        baseline = run([*verify, original], work, f"original-{number}", timeout=60)
        balanced = run([*verify, protected], work, f"balanced-{number}", timeout=60)
        if "WARNING in native method" in balanced.stdout + balanced.stderr:
            raise AssertionError(f"JNI verification reported a warning in round {number}")
        if (balanced.stdout, balanced.stderr) != (baseline.stdout, baseline.stderr):
            raise AssertionError(f"Original and balanced output differ in round {number}. Compare logs in {work}")
    print(f"Concurrent registration and initialization passed in {args.rounds} paired JVM runs")


if __name__ == "__main__":
    main()
