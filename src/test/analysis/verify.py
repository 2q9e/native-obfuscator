#!/usr/bin/env python3
"""Exercise analysis resources through the installed CLI and the host JVM."""

import argparse
import hashlib
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
RESOURCE_ROOT = "META-INF/native-obfuscator/"
MANIFEST = "analysis/manifest.bin"
MAX_SEED = "18446744073709551615"
BALANCED = ["--analysis-resistance", "balanced", "--analysis-seed", MAX_SEED]


def java_tool(name):
    java_home = os.environ.get("JAVA_HOME")
    return Path(java_home) / "bin" / name if java_home else name


def run(command, cwd, expected=0):
    command = [str(argument) for argument in command]
    result = subprocess.run(command, cwd=cwd, encoding="utf-8", errors="replace",
                            capture_output=True, timeout=180)
    if result.returncode != expected:
        raise AssertionError(f"Expected exit {expected}, got {result.returncode}\n{command}\n"
                             f"{result.stdout}\n{result.stderr}")
    return result


def require_text(text, expected):
    if expected.casefold() not in text.casefold():
        raise AssertionError(f"Expected {expected!r} in output\n{text}")


def last_report(result):
    return json.loads(result.stdout.splitlines()[-1])


def entries(jar):
    with zipfile.ZipFile(jar) as archive:
        names = archive.namelist()
        assert len(names) == len(set(names)), f"Duplicate entry in {jar}"
        return {name: archive.read(name) for name in names}


def write_jar(jar, contents):
    with zipfile.ZipFile(jar, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in contents.items():
            archive.writestr(name, data)


def analysis_resources(contents):
    manifests = [name for name in contents if name.startswith(RESOURCE_ROOT) and name.endswith("/" + MANIFEST)]
    assert len(manifests) == 1, manifests
    root = manifests[0].removesuffix(MANIFEST)
    return root, {name[len(root):]: data for name, data in contents.items() if name.startswith(root + "analysis/")}


def check_invalid_options(cli, work, original, common):
    base = ["analyze", "--input", original, *common]
    invalid = (
        (["--analysis-resource-budget", "255"], "--analysis-resource-budget"),
        (["--analysis-resource-budget", "262145"], "--analysis-resource-budget"),
        (["--analysis-entry-budget", "1"], "--analysis-entry-budget"),
        (["--analysis-entry-budget", "33"], "--analysis-entry-budget"),
        (["--analysis-seed", "18446744073709551616"], "--analysis-seed"),
        (["--analysis-helper-methods", "5"], "--analysis-helper-methods"),
        (["--analysis-helpers-per-method", "2"], "--analysis-helpers-per-method"),
        (["--analysis-decoy-methods", "5"], "--analysis-decoy-methods"),
        (["--analysis-llvm-sites", "3"], "--analysis-llvm-sites"),
    )
    for arguments, diagnostic in invalid:
        require_text(cli(*base, "--analysis-resistance", "balanced", *arguments, expected=2).stderr, diagnostic)
    require_text(cli(*base, "--analysis-resistance", "off", "--analysis-entry-budget", "2", expected=2).stderr,
                 "require balanced or maximum")
    require_text(cli(*base, *BALANCED, "--analysis-seed", "1", expected=2).stderr, "Repeated option")

    duplicate = work / "duplicate.json"
    options = {"input": str(original), "include": ["Arithmetic#calc*"], "unsupported": "retain",
               "analysisResistance": "balanced"}
    duplicate.write_text(json.dumps({**options, "analysis-resistance": "balanced"}), encoding="utf-8")
    require_text(cli("analyze", "--config", duplicate, expected=2).stderr, "Repeated option")
    repeated_member = json.dumps(options).removesuffix("}") + ', "analysisResistance": "balanced"}'
    duplicate.write_text(repeated_member, encoding="utf-8")
    require_text(cli("analyze", "--config", duplicate, expected=1).stderr, "Duplicate config field")


def check_no_writes(cli, work, original, common):
    before = set(work.rglob("*"))
    original_bytes = original.read_bytes()
    analyzed = cli("analyze", "--input", original, *common, *BALANCED)
    assert any(json.loads(line).get("result") == "eligible" for line in analyzed.stdout.splitlines())
    dry_run = last_report(cli("harden", "--input", original, "--output", work / "dry-run.jar",
                              *common, *BALANCED, "--dry-run"))
    assert dry_run["status"] == "dry-run-complete" and dry_run["writesOutput"] is False, dry_run
    assert dry_run["eligibleMethods"] > 0, dry_run
    assert set(work.rglob("*")) == before, "Analyze or dry run created files"
    assert original.read_bytes() == original_bytes, "Analyze or dry run altered the original JAR"


def check_source_output(cli, work, original, common):
    output = work / "preview.c"
    before = set(work.rglob("*"))
    emitted = last_report(cli("protect", "--input", original, "--output", output,
                              *common, *BALANCED, "--emit-c"))
    assert emitted["status"] == "c-emitted-resources-omitted", emitted
    assert emitted["binding"] == "requires-packaged-jar-runtime" and "artifact" not in emitted, emitted
    assert set(work.rglob("*")) - before == {output}, "Emit-C produced an unexpected file"
    generated = output.read_text(encoding="utf-8")
    assert "_registerBoundV1" in generated and "check_analysis_binding" in generated, generated

    invalid = ["harden", "--input", original, "--output", output, *common, *BALANCED, "--dry-run"]
    require_text(cli(*invalid, "--emit-c", expected=2).stderr, "cannot use --emit-c")
    require_text(cli(*invalid, "--dry-run", expected=2).stderr, "Repeated option --dry-run")
    require_text(cli(*invalid, "--profile", "maximum", expected=2).stderr, "Specify --clang")
    assert output.read_text(encoding="utf-8") == generated, "Rejected dry run changed output"


def check_workflow(cli, execute, work, original, common, compiler, baseline, balanced_corpus):
    staged = work / "staged.jar"
    composed = [*common, *BALANCED, "--padding-count", "1", "--padding-seed", "92",
                "--markers", "3", "--marker-seed", "73"]
    harden = last_report(cli("harden", "--input", original, "--output", staged, *composed, *compiler))
    assert harden["status"] == "staged" and harden["verification"]["structurallyValid"], harden
    generated = harden["verification"]["expectedGeneratedClasses"]
    assert len(generated) == 3, harden
    staged_entries = entries(staged)
    root, corpus = analysis_resources(staged_entries)
    assert corpus == balanced_corpus, "Padding or markers changed the analysis corpus"
    markers = staged_entries[root + "markers.txt"].splitlines()
    assert len(markers) == 3 and len(set(markers)) == 3, markers
    assert execute(staged).stdout == baseline, "Composed staged JAR changed application output"

    checked = last_report(cli("verify", "--input", original, "--staged", staged, *composed))
    assert checked["status"] == "verified" and checked["verification"]["structurallyValid"], checked
    release = work / "release.jar"
    state = work / "state"
    shutil.copyfile(original, release)
    applied = last_report(cli("apply", "--input", original, "--staged", staged, "--output", release,
                              "--state-dir", state, "--confirm-overwrite", *composed))
    assert applied["status"] == "applied" and release.read_bytes() == staged.read_bytes(), applied
    assert execute(release).stdout == baseline, "Promoted JAR changed application output"
    restored = last_report(cli("restore", "--output", release, "--state-dir", state))
    assert restored["status"] == "restored" and release.read_bytes() == original.read_bytes(), restored
    assert execute(release).stdout == baseline, "Restored JAR changed application output"


def check_mutations(cli, execute, work, original, common, protected, donor):
    root, corpus = analysis_resources(protected)
    _, donor_corpus = analysis_resources(donor)
    assert donor_corpus[MANIFEST] != corpus[MANIFEST], "Different seed produced the same manifest"
    manifest = root + MANIFEST
    resource = root + next(name for name in corpus if name.endswith(".txt"))
    other = root + next(name for name in corpus if name.endswith(".tsv"))
    libraries = [name for name in protected if name.startswith(root) and name.endswith((".dll", ".so"))]
    assert len(libraries) == 1, libraries
    library = libraries[0]

    def reject_case(label, contents, verification_error, runtime_error=None):
        candidate = work / (label + ".jar")
        write_jar(candidate, contents)
        rejected = last_report(cli("verify", "--input", original, "--staged", candidate, *common,
                                   *BALANCED, expected=1))
        verification = rejected["verification"]
        assert rejected["status"] == "verification-failed" and not verification["structurallyValid"], rejected
        require_text("\n".join(verification["errors"]), verification_error)
        if runtime_error:
            error = execute(candidate, expected=1).stderr
            require_text(error, "UnsatisfiedLinkError")
            require_text(error, runtime_error)
        return candidate

    reject_case("missing-manifest", {k: v for k, v in protected.items() if k != manifest},
                "Analysis resources", "Cannot verify")
    reject_case("missing-resource", {k: v for k, v in protected.items() if k != resource},
                "Analysis resources", "Cannot verify")
    data = protected[resource]
    replacement = bytes([data[0] ^ 1]) + data[1:]
    reject_case("replaced-resource", {**protected, resource: replacement}, "Analysis resources", "checksum mismatch")
    reject_case("swapped-resources", {**protected, resource: protected[other], other: protected[resource]},
                "Analysis resources", "Cannot verify")
    oversize = b"x" * (32 * 1024 + 1)
    reject_case("oversized-resource", {**protected, resource: oversize},
                "Analysis resources exceed the configured entry or byte limits", "length mismatch")

    extra = root + "analysis/extra.txt"
    reject_case("extra-resource", {**protected, extra: b"unexpected\n"}, "analysis/extra.txt")
    extra_sidecar = library + ".extra.sha256"
    reject_case("extra-sidecar", {**protected, extra_sidecar: protected[library + ".sha256"]}, ".extra.sha256")
    corpus_sidecar = resource + ".sha256"
    reject_case("corpus-sidecar", {**protected, corpus_sidecar: hashlib.sha256(data).hexdigest().encode("ascii")},
                corpus_sidecar)

    donor_manifest = hashlib.sha256(donor_corpus[MANIFEST]).digest()
    old_anchor = b"NOAB\x01" + hashlib.sha256(corpus[MANIFEST]).digest()
    binary = protected[library]
    assert b".noab" in binary and binary.count(old_anchor) == 1, "Native analysis anchor missing or duplicated"
    updated = binary.replace(old_anchor, b"NOAB\x01" + donor_manifest)
    checksum = hashlib.sha256(updated).hexdigest().encode("ascii")
    reject_case("changed-anchor", {**protected, library: updated, library + ".sha256": checksum},
                "Native analysis binding", "Native analysis binding")

    registrations = sorted(
        (int(name.rsplit("/", 1)[1].removesuffix(".bin")), data)
        for name, data in protected.items() if name.startswith(root + "registration/"))
    assert [index for index, _ in registrations] == list(range(len(registrations))), registrations
    registration_binding = (b"NOAR\x01" + len(registrations).to_bytes(4, "big")
                            + b"".join(hashlib.sha256(data).digest() for _, data in registrations))
    assert binary.count(registration_binding) == 1, "Expected exactly one registration digest table"
    changed_registration = (registration_binding[:9] + bytes([registration_binding[9] ^ 1])
                            + registration_binding[10:])
    bound_library = binary.replace(registration_binding, changed_registration)
    bound_checksum = hashlib.sha256(bound_library).hexdigest().encode("ascii")
    reject_case("changed-registration-binding", {**protected, library: bound_library, library + ".sha256": bound_checksum},
                "Native analysis binding", "Native method registration failed")

    utility_sections = sorted(set(re.findall(rb"\.nou[0-9a-f]{3}\0", binary)))
    assert len(utility_sections) == 2, utility_sections
    missing_utility = binary.replace(utility_sections[0], b".bad000\0")
    utility_checksum = hashlib.sha256(missing_utility).hexdigest().encode("ascii")
    reject_case("missing-utility-section", {**protected, library: missing_utility, library + ".sha256": utility_checksum},
                "Native utility section")

    registration = ("Java_dev_nativeobfuscator_runtime_" + root.rstrip("/").rsplit("/", 1)[1]
                    + "_registerBoundV1").encode("ascii")
    assert binary.count(registration) == 1, "Expected one stripped JNI registration export"
    renamed = binary.replace(registration, registration[:-1] + b"2")
    renamed_checksum = hashlib.sha256(renamed).hexdigest().encode("ascii")
    reject_case("renamed-export", {**protected, library: renamed, library + ".sha256": renamed_checksum},
                "native export", "registerBoundV1")

    alternate = {name: data for name, data in protected.items() if not name.startswith(root + "analysis/")}
    alternate.update({root + name: data for name, data in donor_corpus.items()})
    candidate = reject_case("alternate-corpus", alternate, "Analysis resources", "Native analysis binding")
    release = work / "guarded-release.jar"
    state = work / "guarded-state"
    shutil.copyfile(original, release)
    refused = last_report(cli("apply", "--input", original, "--staged", candidate, "--output", release,
                              "--state-dir", state, "--confirm-overwrite", *common, *BALANCED, expected=1))
    assert refused["status"] == "verification-failed", refused
    assert release.read_bytes() == original.read_bytes() and not state.exists(), "Rejected apply changed release state"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("distribution", type=Path, nargs="?", default=BUILD / "install" / "native-obfuscator")
    parser.add_argument("--zig", type=Path, help="path to Zig for Windows or cross compilation")
    args = parser.parse_args()
    if not (args.distribution / "lib" / "native-obfuscator.jar").is_file():
        parser.error("Build installDist or pass an installed distribution directory")
    if os.name == "nt" and args.zig is None:
        parser.error("Pass --zig with the Zig executable path on Windows")
    if args.zig is not None and not args.zig.is_file():
        parser.error("--zig must point to an existing executable")

    BUILD.mkdir(exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="analysis-integration-", dir=BUILD))
    print(f"Analysis integration artifacts at {work}", flush=True)
    java_tmp = work / "tmp"
    java_tmp.mkdir()
    java = [java_tool("java"), f"-Djava.io.tmpdir={java_tmp}"]
    tool = [*java, "-cp", args.distribution.resolve() / "lib" / "*", "dev.nativeobfuscator.Main"]
    zig = str(args.zig.resolve()) if args.zig else None
    compiler = ["--zig", zig] if zig else []

    def cli(*arguments, expected=0):
        return run([*tool, *arguments], work, expected)

    def execute(jar, expected=0):
        return run([*java, "-Xverify:all", "-Xcheck:jni", "--enable-native-access=ALL-UNNAMED", "-jar", jar],
                   work, expected)

    classes = work / "classes"
    classes.mkdir()
    original = work / "original.jar"
    run([java_tool("javac"), "--release", "21", "-encoding", "UTF-8", "-Xlint:all", "-Werror", "-d", classes,
         ROOT / "src" / "test" / "fixtures" / "Arithmetic.java"], work)
    run([java_tool("jar"), "--create", "--file", original, "--main-class", "Arithmetic", "-C", classes, "."], work)
    baseline = execute(original).stdout
    assert baseline.strip(), "Arithmetic fixture did not produce output"
    common = ["--include", "Arithmetic#calc*", "--unsupported", "retain"]
    check_invalid_options(cli, work, original, common)
    check_no_writes(cli, work, original, common)
    check_source_output(cli, work, original, common)

    def protect(jar, settings):
        cli("protect", "--input", original, "--output", jar, *common, "--padding", "off", *settings, *compiler)
        return entries(jar)

    protected_jar = work / "balanced.jar"
    protected = protect(protected_jar, BALANCED)
    root, corpus = analysis_resources(protected)
    assert root + "markers.txt" not in protected, "Analysis enabled markers without an option"
    assert any(name.endswith(".txt") for name in corpus) and any(name.endswith(".tsv") for name in corpus), corpus.keys()
    catalog = corpus["analysis/component-index.tsv"].decode("ascii").splitlines()
    assert catalog[0] == "# Synthetic utility catalog v1" and len(catalog) == 4, catalog
    for row in catalog[2:]:
        component, family, prototype, raw_digest, sample = row.split("\t")
        assert len(component) == 16 and raw_digest.startswith(component), row
        assert family and prototype.startswith("(") and "analysis/" + sample in corpus, row
    for name, data in corpus.items():
        if name != MANIFEST:
            assert data.decode("ascii").endswith("\n"), name
    assert execute(protected_jar).stdout == baseline, "Balanced JAR changed application output"
    replica = work / "balanced-replica.jar"
    protect(replica, BALANCED)
    assert replica.read_bytes() == protected_jar.read_bytes(), "Same analysis seed produced different JAR bytes"

    off_jar = work / "off.jar"
    off = protect(off_jar, ["--analysis-resistance", "off"])
    assert not any(name.startswith(RESOURCE_ROOT) and "/analysis/" in name for name in off), "Off emitted a corpus"
    assert execute(off_jar).stdout == baseline, "Off JAR changed application output"
    donor_jar = work / "alternate-seed.jar"
    donor = protect(donor_jar, ["--analysis-resistance", "balanced", "--analysis-seed", "0"])
    assert execute(donor_jar).stdout == baseline, "Alternate-seed donor JAR failed at runtime"
    check_mutations(cli, execute, work, original, common, protected, donor)
    check_workflow(cli, execute, work, original, common, compiler, baseline, corpus)

    maximum = work / "maximum.jar"
    config = work / "maximum.json"
    options = {"input": str(original), "output": str(maximum), "include": ["Arithmetic#calc*"],
               "unsupported": "retain", "padding": "off", "analysisResistance": "maximum",
               "analysisSeed": int(MAX_SEED), "analysisResourceBudget": 256, "analysisEntryBudget": 2,
               "analysisHelperMethods": 1, "analysisHelpersPerMethod": 2, "analysisDecoyMethods": 1,
               "analysisLlvmSites": 0}
    if zig:
        options["zig"] = zig
    config.write_text(json.dumps(options), encoding="utf-8")
    cli("protect", "--config", config)
    _, capped = analysis_resources(entries(maximum))
    assert len(capped) == 2 and sum(map(len, capped.values())) <= 256, capped.keys()
    assert execute(maximum).stdout == baseline, "Maximum JAR changed application output"
    print("Analysis integration checks passed")


if __name__ == "__main__":
    main()
