#!/usr/bin/env python3
"""Package the Gradle distributions and describe their shipped Java dependencies."""

import argparse
from datetime import datetime, timezone
import gzip
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import tarfile
from urllib.parse import quote
from xml.etree import ElementTree
import zipfile


NUMBER = r"(?:0|[1-9][0-9]*)"
IDENTIFIER = r"[0-9A-Za-z-]+"
TAG = re.compile(
    rf"v({NUMBER}\.{NUMBER}\.{NUMBER})(?:-({IDENTIFIER}(?:\.{IDENTIFIER})*))?"
    rf"(?:\+({IDENTIFIER}(?:\.{IDENTIFIER})*))?"
)
LICENSES = {
    "com.google.code.gson": "Apache-2.0",
    "com.google.errorprone": "Apache-2.0",
    "org.ow2.asm": "BSD-3-Clause",
}
GRADLE_XML = {"gradle": "https://schema.gradle.org/dependency-verification"}
ROOT_JAR = "native-obfuscator.jar"


def version(tag):
    match = TAG.fullmatch(tag)
    if match is None or any(
        token.startswith("0") and len(token) > 1 and token.isdigit()
        for token in (match.group(2) or "").split(".")
    ):
        raise ValueError(f"Expected a v-prefixed Semantic Versioning tag, got {tag!r}")
    return tag[1:]


def is_library(name):
    parts = PurePosixPath(name).parts
    return len(parts) == 3 and parts[:2] == ("native-obfuscator", "lib") and parts[2].endswith(".jar")


def jar_hashes(distribution, is_zip):
    result = {}
    with (zipfile.ZipFile(distribution) if is_zip else tarfile.open(distribution, "r:")) as archive:
        if is_zip:
            entries = ((item.filename, item, not item.is_dir()) for item in archive.infolist())
        else:
            entries = ((item.name, item, item.isfile()) for item in archive)
        for path, item, regular in entries:
            if not is_library(path):
                continue
            name = PurePosixPath(path).name
            if not regular or name in result:
                raise ValueError(f"Invalid or duplicate distributed JAR {path}")
            open_member = archive.open if is_zip else archive.extractfile
            with open_member(item) as jar:
                result[name] = hashlib.file_digest(jar, "sha256").hexdigest()
    if ROOT_JAR not in result:
        raise ValueError(f"{distribution} does not contain {ROOT_JAR}")
    return result


def coordinates(metadata, jars):
    components = {}
    root = ElementTree.parse(metadata)
    for component in root.findall("./gradle:components/gradle:component", GRADLE_XML):
        group, name, revision = (component.attrib[key] for key in ("group", "name", "version"))
        for entry in component.findall("gradle:artifact", GRADLE_XML):
            filename = entry.attrib["name"]
            if filename not in jars:
                continue
            hashes = {child.attrib["value"] for child in entry.findall("gradle:sha256", GRADLE_XML)}
            if jars[filename] not in hashes:
                continue
            if filename in components:
                raise ValueError(f"Ambiguous coordinates for {filename}")
            if group not in LICENSES:
                raise ValueError(f"No release license recorded for {group}:{name}")
            components[filename] = (group, name, revision)
    if missing := jars.keys() - {ROOT_JAR} - components.keys():
        raise ValueError(f"Distributed JARs missing an exact Gradle SHA-256 pin: {', '.join(sorted(missing))}")
    return components


def package_entry(name, version_info, file, digest, license_id, identifier, download, purl):
    return {
        "SPDXID": identifier, "name": name, "versionInfo": version_info, "packageFileName": file,
        "filesAnalyzed": False, "downloadLocation": download, "licenseConcluded": license_id,
        "licenseDeclared": license_id, "copyrightText": "NOASSERTION",
        "checksums": [{"algorithm": "SHA256", "checksumValue": digest}],
        "externalRefs": [{"referenceCategory": "PACKAGE-MANAGER", "referenceType": "purl",
                          "referenceLocator": purl}],
    }


def spdx(tag, epoch, jars, components, repository):
    revision = version(tag)
    repo_url = f"https://github.com/{repository}"
    project_id = "SPDXRef-NativeObfuscator"
    packages = [package_entry(
        "native-obfuscator", revision, ROOT_JAR, jars[ROOT_JAR], "MIT", project_id,
        f"{repo_url}/releases/download/{tag}/native-obfuscator-{tag}.zip",
        f"pkg:github/{repository}@{quote(tag, safe='')}",
    )]
    relationships = [{"spdxElementId": "SPDXRef-DOCUMENT", "relationshipType": "DESCRIBES",
                      "relatedSpdxElement": project_id}]
    for filename, (group, name, component_version) in sorted(components.items()):
        identifier = "SPDXRef-" + re.sub(r"[^a-zA-Z0-9.-]", "-", f"{group}-{name}-{component_version}")
        relative = f"{group.replace('.', '/')}/{name}/{component_version}/{filename}"
        packages.append(package_entry(
            f"{group}:{name}", component_version, filename, jars[filename], LICENSES[group], identifier,
            f"https://repo.maven.apache.org/maven2/{relative}", f"pkg:maven/{group}/{name}@{component_version}",
        ))
        relationships.append({"spdxElementId": project_id, "relationshipType": "DEPENDS_ON",
                              "relatedSpdxElement": identifier})
    fingerprint = hashlib.sha256(json.dumps(jars, sort_keys=True).encode()).hexdigest()
    return {
        "spdxVersion": "SPDX-2.3", "dataLicense": "CC0-1.0", "SPDXID": "SPDXRef-DOCUMENT",
        "name": f"native-obfuscator-{tag}",
        "documentNamespace": f"{repo_url}/releases/tag/{tag}/spdx-{fingerprint}",
        "creationInfo": {"created": datetime.fromtimestamp(epoch, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
                         "creators": ["Tool: native-obfuscator-release"]},
        "packages": packages, "relationships": relationships,
    }


def package(args):
    version(args.tag)
    if not re.fullmatch(r"[a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+", args.repository):
        raise ValueError("Expected a GitHub owner/repository")
    if args.epoch < 0:
        raise ValueError("SOURCE_DATE_EPOCH must be non-negative")

    jars = jar_hashes(args.zip, True)
    if jars != jar_hashes(args.tar, False):
        raise ValueError("ZIP and TAR distributions do not contain the same JARs")
    document = spdx(args.tag, args.epoch, jars, coordinates(args.metadata, jars), args.repository)
    args.output.mkdir(parents=True, exist_ok=True)
    base = f"native-obfuscator-{args.tag}"
    shutil.copyfile(args.zip, args.output / f"{base}.zip")
    with args.tar.open("rb") as tar, (args.output / f"{base}.tar.gz").open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as gzip_tar:
            shutil.copyfileobj(tar, gzip_tar)
    (args.output / f"{base}.spdx.json").write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="action", required=True)
    commands.add_parser("version").add_argument("tag")
    create = commands.add_parser("package")
    create.add_argument("--tag", required=True)
    create.add_argument("--epoch", required=True, type=int)
    create.add_argument("--repository", required=True)
    for option in ("zip", "tar", "metadata", "output"):
        create.add_argument(f"--{option}", required=True, type=Path)
    args = parser.parse_args()
    try:
        if args.action == "version":
            print(version(args.tag))
        else:
            package(args)
    except (ValueError, OSError, ElementTree.ParseError, tarfile.TarError, zipfile.BadZipFile) as failure:
        parser.error(str(failure))


if __name__ == "__main__":
    main()
