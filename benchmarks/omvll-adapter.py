#!/usr/bin/env python3
"""Build the common C workload with or without O-MVLL on Android ARM64."""

import argparse
import hashlib
import os
from pathlib import Path
import shlex
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--clang", required=True, type=Path, help="absolute path to the Android NDK Clang")
    parser.add_argument("--plugin", type=Path, help="omit to build the matched baseline")
    parser.add_argument("--python-lib", type=Path, help="Python Lib directory from the O-MVLL release")
    parser.add_argument("--qemu", type=Path, help="qemu-x86_64 executable for an ARM64 build host")
    parser.add_argument("--qemu-root", type=Path, help="x86-64 Linux libraries and dynamic loader")
    parser.add_argument("--version", action="store_true")
    parser.add_argument("input", nargs="?", type=Path)
    parser.add_argument("output", nargs="?", type=Path)
    args = parser.parse_args()
    if bool(args.plugin) != bool(args.python_lib):
        parser.error("--plugin and --python-lib must be supplied together")
    if bool(args.qemu) != bool(args.qemu_root):
        parser.error("--qemu and --qemu-root must be supplied together")

    clang = args.clang.resolve()
    prefix = [args.qemu.resolve(), "-L", args.qemu_root.resolve()] if args.qemu else []
    if args.version:
        subprocess.run([*prefix, clang, "--version"], check=True)
        if args.qemu:
            subprocess.run([prefix[0], "--version"], check=True)
        if args.plugin:
            with args.plugin.open("rb") as file:
                print("O-MVLL plugin SHA-256", hashlib.file_digest(file, "sha256").hexdigest())
        return
    if args.input is None or args.output is None:
        parser.error("input and output are required for a build")

    environment = dict(os.environ)
    plugin_flags = []
    if args.plugin:
        environment.update(OMVLL_CONFIG=str(Path(__file__).resolve().with_name("omvll-arithmetic.py")),
                           OMVLL_PYTHONPATH=str(args.python_lib.resolve()), PYTHONDONTWRITEBYTECODE="1")
        plugin_flags.append("-fpass-plugin=" + str(args.plugin.resolve()))
    with tempfile.TemporaryDirectory(prefix="omvll-build-") as scratch:
        environment["TMPDIR"] = scratch
        # With QEMU user-mode, the driver cannot implicitly spawn the x86-64
        # compiler and linker. Obtain their arguments from the driver and run
        # both jobs explicitly. Use the same sequence for both configurations.
        plan = subprocess.run(
            [*prefix, clang, "-###", "--target=aarch64-linux-android24", "-std=c11", "-O2", "-static",
             *plugin_flags, args.input.resolve(), "-o", args.output.resolve()],
            text=True, capture_output=True, env=environment, check=True,
        )
        jobs = [shlex.split(line) for line in plan.stderr.splitlines() if line.startswith(' "')]
        if (len(jobs) != 2 or jobs[0][1:2] != ["-cc1"]
            or Path(jobs[0][0]).resolve() != clang
            or Path(jobs[1][0]).resolve() != (clang.parent / "ld.lld").resolve()):
            raise RuntimeError(f"Unexpected compiler or linker plan:\n{plan.stderr}")
        compiler_job = [item for item in jobs[0] if item != "-disable-llvm-verifier"]
        subprocess.run([*prefix, *compiler_job], env=environment, check=True)
        subprocess.run([*prefix, *jobs[1]], env=environment, check=True)


if __name__ == "__main__":
    main()
