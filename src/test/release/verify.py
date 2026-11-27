#!/usr/bin/env python3
"""Validate actual release distributions and negative packaging cases."""

import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / "scripts/release_assets.py"
TAG = "v1.2.3-rc.1"


def run(*arguments, expected=0):
    result = subprocess.run([sys.executable, SCRIPT, *arguments], capture_output=True, text=True, timeout=30)
    if result.returncode != expected:
        raise AssertionError(f"{arguments}: {result.returncode}\n{result.stdout}\n{result.stderr}")
    return result


def main():
    for tag in ("v1.2.3", TAG, "v1.2.3+build-7", "v0.0.0-0"):
        assert run("version", tag).stdout.strip() == tag[1:]
    for tag in ("1.2.3", "v01.2.3", "v1.2.3-01", "v1.2.3-a..b", "v1.2.3+"):
        run("version", tag, expected=2)
    dist = ROOT / "build/distributions"
    archive = dist / "native-obfuscator.zip"
    metadata = ROOT / "gradle/verification-metadata.xml"
    with tempfile.TemporaryDirectory() as directory:
        work = Path(directory)
        common = ("package", "--tag", TAG, "--epoch", "1700000000", "--repository", "2q9e/native-obfuscator",
                  "--zip", archive, "--tar", dist / "native-obfuscator.tar", "--metadata", metadata)
        first, second = work / "first", work / "second"
        run(*common, "--output", first)
        run(*common, "--output", second)
        for asset in first.iterdir():
            assert asset.read_bytes() == (second / asset.name).read_bytes()
        spdx = json.loads((first / f"native-obfuscator-{TAG}.spdx.json").read_text())
        assert spdx["spdxVersion"] == "SPDX-2.3"
        packages = {package["packageFileName"]: package for package in spdx["packages"]}
        with zipfile.ZipFile(archive) as original:
            jars = {name.removeprefix("native-obfuscator/lib/"): original.read(name)
                    for name in original.namelist() if name.startswith("native-obfuscator/lib/") and name.endswith(".jar")}
        assert packages.keys() == jars.keys() and len(jars) >= 2
        for name, data in jars.items():
            assert packages[name]["checksums"] == [{"algorithm": "SHA256", "checksumValue": hashlib.sha256(data).hexdigest()}]
        assert packages["native-obfuscator.jar"]["versionInfo"] == TAG[1:]
        identifiers = {item["SPDXID"] for item in packages.values()}
        assert identifiers == {rel["relatedSpdxElement"] for rel in spdx["relationships"]}
        altered = work / "altered.xml"
        altered.write_text(metadata.read_text().replace(
            hashlib.sha256(jars["asm-9.9.jar"]).hexdigest(), "0" * 64))
        result = run(*common, "--metadata", altered, "--output", work / "invalid", expected=2)
        assert "missing an exact Gradle SHA-256 pin" in result.stderr
        assert not (work / "invalid").exists()
    print("Semantic versions, reproducible release assets, and SPDX hashes verified")


if __name__ == "__main__":
    main()
