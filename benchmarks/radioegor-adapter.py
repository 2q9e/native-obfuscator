#!/usr/bin/env python3
"""Generate, compile and package a Radioegor146 JNI library for the comparison fixture."""

import argparse
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import tempfile


MAIN = "by.radioegor146.Main"
RESOURCE = "comparison-native"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--classpath", required=True, help="absolute classpath for the pinned Radioegor build")
    parser.add_argument("--revision", required=True, help="source revision used to build the tool")
    parser.add_argument("--cmake", default="cmake")
    parser.add_argument("--cxx", default=os.environ.get("CXX", "c++"))
    parser.add_argument("--version", action="store_true")
    parser.add_argument("input", nargs="?", type=Path)
    parser.add_argument("output", nargs="?", type=Path)
    parser.add_argument("selection", nargs="?", type=Path)
    args = parser.parse_args()
    os_name = platform.system()
    extension = ".exe" if os_name == "Windows" else ""
    java_home = os.environ.get("JAVA_HOME")
    java_bin = Path(java_home).resolve() / "bin" if java_home else Path()
    tool = [str(java_bin / ("java" + extension)), "-cp", args.classpath, MAIN]
    if args.version:
        print(f"Radioegor146 source revision {args.revision}", flush=True)
        subprocess.run([*tool, "--version"], check=True)
        subprocess.run([args.cmake, "--version"], check=True)
        subprocess.run([args.cxx, "--version"], check=True)
        return
    if not all((args.input, args.output, args.selection)):
        parser.error("input, output and selection are required")
    arch = {"x86_64": "x64", "amd64": "x64", "aarch64": "arm64", "arm64": "arm64"}.get(platform.machine().lower())
    if not arch or os_name not in {"Windows", "Linux"}:
        parser.error(f"unsupported host {platform.machine()}-{os_name}")
    binary = "native_library.dll" if os_name == "Windows" else "libnative_library.so"
    target = f"{arch}-{'windows.dll' if os_name == 'Windows' else 'linux.so'}"
    selected, owners = [], set()
    for line in args.selection.read_text(encoding="utf-8").splitlines():
        match = re.fullmatch(r"([^#!()]+)#[^#!()]+\([^()]*\).+", line)
        if not match:
            parser.error(f"invalid selected method {line!r}")
        owners.add(match[1])
        selected.append(line.replace("(", "!(", 1))
    if not selected:
        parser.error("the selection contains no methods")
    with tempfile.TemporaryDirectory(prefix="radioegor-bench-", dir=Path.cwd()) as work:
        scratch = Path(work)
        whitelist = scratch / "whitelist.txt"
        # The upstream class filter requires the owner separately. Class initialization
        # emits JNI registration even for owners without a declared initializer.
        entries = [*sorted(owners), *selected, *(f"{owner}#<clinit>!()V" for owner in sorted(owners))]
        whitelist.write_text("\n".join(entries) + "\n", encoding="utf-8")
        generated = scratch / "generated"
        build = scratch / "native"
        subprocess.run([*tool, "--custom-lib-dir", RESOURCE, "-w", whitelist, args.input, generated], check=True)
        subprocess.run([args.cmake, "-G", "Ninja", "-S", generated / "cpp", "-B", build,
                        "-DCMAKE_BUILD_TYPE=Release", f"-DCMAKE_CXX_COMPILER={args.cxx}"], check=True)
        subprocess.run([args.cmake, "--build", build], check=True)
        resources = scratch / RESOURCE
        resources.mkdir()
        shutil.copyfile(build / "build/lib" / binary, resources / target)
        shutil.copyfile(generated / args.input.name, args.output)
        subprocess.run([str(java_bin / ("jar" + extension)), "--update", "--date=2020-01-01T00:00:00Z",
                        "--file", args.output, "-C", scratch, f"{RESOURCE}/{target}"], check=True)


if __name__ == "__main__":
    main()
