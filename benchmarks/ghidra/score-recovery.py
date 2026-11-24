#!/usr/bin/env python3
"""Compare headless Ghidra's helper calls with verified linked edges from the exact staged JAR."""

import argparse
import hashlib
import json
from pathlib import Path
import re
from zipfile import ZipFile


ROW = re.compile(r"(HELPER_(?:INPUT|RESULT)\t.*?) \(GhidraScript\)\s*$")
PHASES = ("auto", "section-entry")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(stream):
    digest = hashlib.sha256()
    for block in iter(lambda: stream.read(1024 * 1024), b""):
        digest.update(block)
    return digest.hexdigest()


def read_cases(log):
    cases = {}
    phases = None
    for line in log.read_text(encoding="utf-8").splitlines():
        match = ROW.search(line)
        if not match:
            continue
        fields = match[1].split("\t")
        if fields[0] == "HELPER_INPUT":
            _, binary, digest, count = fields
            require(binary not in cases, f"Duplicate input {binary}")
            phases = {phase: {} for phase in PHASES}
            cases[binary] = (digest.lower(), int(count), phases)
        else:
            _, phase, section, _, found, decompiled, calls = fields
            require(phases is not None and phase in phases and section not in phases[phase]
                    and found in {"true", "false"} and decompiled in {"true", "false"}, f"Unexpected row for {section}")
            phases[phase][section] = (found == "true", decompiled == "true", calls.split(",") if calls else [])
    require(cases, "No Ghidra helper records found")
    return cases


def score(staged_jar, verification_log, ghidra_log):
    final = json.loads(verification_log.read_text(encoding="utf-8").splitlines()[-1])["verification"]
    with staged_jar.open("rb") as stream:
        digest = sha256(stream)
    require(final["structurallyValid"] and final["stagedSha256"] == digest, "Verification report does not match staged JAR")
    expected = {
        target["target"]: {(edge["sourceSection"], edge["targetSection"])
                           for helper in target["helpers"] for edge in helper["edges"]
                           if edge["status"] == "verified"}
        for target in final["nativeHelpers"]
    }
    results = {}
    with ZipFile(staged_jar) as archive:
        for binary, (binary_digest, count, phases) in read_cases(ghidra_log).items():
            target = binary.removesuffix("-libprotected.so").removesuffix("-protected.dll")
            require(target in expected and target not in results, f"Unexpected input binary name {binary}")
            filename = "libprotected.so" if target.endswith("-linux") else "protected.dll"
            require(binary == f"{target}-{filename}", f"Unexpected input binary name {binary}")
            matches = [name for name in archive.namelist() if name.endswith(f"/{target}/{filename}")]
            require(len(matches) == 1, f"Expected one native binary for {target}")
            with archive.open(matches[0]) as stream:
                require(sha256(stream) == binary_digest, f"Analyzed binary for {target} does not match staged JAR")
            edges = expected[target]
            sections = {section for edge in edges for section in edge}
            require(count == len(sections), f"Section count differs from verified graph for {target}")
            summary = {}
            for phase, rows in phases.items():
                require(rows.keys() == sections, f"Missing or unexpected helper entries for {target} {phase}")
                observed = {(section, dest) for section, (_, _, calls) in rows.items() for dest in calls}
                summary[phase] = {
                    "entriesDiscovered": sum(found for found, _, _ in rows.values()),
                    "functionsDecompiled": sum(decompiled for _, decompiled, _ in rows.values()),
                    "exactEdgesRecovered": len(observed & edges),
                    "missingEdges": sorted(edges - observed),
                    "otherHelperEdges": sorted(observed - edges),
                }
            results[target] = {"sha256": binary_digest, "visibleSections": count,
                               "verifiedEdges": len(edges), "phases": summary}
    return {"stagedSha256": digest, "targets": results}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("staged_jar", type=Path)
    parser.add_argument("verification_log", type=Path)
    parser.add_argument("ghidra_log", type=Path)
    args = parser.parse_args()
    print(json.dumps(score(args.staged_jar, args.verification_log, args.ghidra_log), indent=2))


if __name__ == "__main__":
    main()
