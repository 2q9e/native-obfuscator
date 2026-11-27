#!/usr/bin/env python3
"""Build the C comparison workload with a matched LLVM frontend and backend."""

import argparse
import hashlib
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--clang", required=True)
    parser.add_argument("--opt")
    parser.add_argument("--plugin", type=Path)
    parser.add_argument("--passes")
    parser.add_argument("--version", action="store_true")
    parser.add_argument("input", nargs="?")
    parser.add_argument("output", nargs="?")
    args = parser.parse_args()
    llvm = (args.opt, args.plugin, args.passes)
    if any(llvm) and not all(llvm):
        parser.error("opt, plugin, and passes must be supplied together")
    if args.version:
        subprocess.run([args.clang, "--version"], check=True)
        if args.opt:
            subprocess.run([args.opt, "--version"], check=True)
            with args.plugin.open("rb") as stream:
                print(f"Pass plugin SHA-256 {hashlib.file_digest(stream, 'sha256').hexdigest()}")
        return
    if not args.input or not args.output:
        parser.error("input and output are required for a build")
    with tempfile.TemporaryDirectory(prefix="llvm-benchmark-", dir=Path.cwd()) as work:
        baseline = Path(work) / "baseline.bc"
        subprocess.run([args.clang, "-std=c11", "-O2", "-emit-llvm", "-c", args.input, "-o", baseline], check=True)
        backend = baseline
        if args.opt:
            backend = Path(work) / "protected.bc"
            subprocess.run([args.opt, "-verify-each", f"-load-pass-plugin={args.plugin}",
                            f"-passes={args.passes},verify", baseline, "-o", backend], check=True)
        subprocess.run([args.clang, "-O0", backend, "-o", args.output], check=True)


if __name__ == "__main__":
    main()
