#!/usr/bin/env bash
set -euo pipefail

if (( $# < 1 )); then
    echo "Usage: bash src/test/integration.sh /path/to/native-obfuscator [protect compiler arguments]" >&2
    exit 2
fi
tool=$1
shift
java_home=${JAVA_HOME:?Set JAVA_HOME to the JDK under test}
release=${JAVA_RELEASE:-21}
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
mkdir "$work/classes"
"$java_home/bin/javac" --release "$release" -Xlint:all -Werror -d "$work/classes" "$root/src/test/fixtures/Arithmetic.java"
"$java_home/bin/jar" --create --file "$work/original.jar" --main-class Arithmetic -C "$work/classes" .
"$java_home/bin/java" -Xverify:all -jar "$work/original.jar" > "$work/original.txt"

"$tool" analyze --input "$work/original.jar" --include 'Arithmetic#calc*' --unsupported retain > "$work/analysis.jsonl"
"$tool" protect --input "$work/original.jar" --output "$work/protected.jar" --include 'Arithmetic#calc*' \
    --unsupported retain "$@" > "$work/report.jsonl"
"$java_home/bin/java" -Xverify:all -Xcheck:jni --enable-native-access=ALL-UNNAMED \
    -jar "$work/protected.jar" > "$work/protected.txt"
cmp "$work/original.txt" "$work/protected.txt"
"$java_home/bin/javap" -p -classpath "$work/protected.jar" Arithmetic > "$work/methods.txt"
grep -q 'static native int calcOverload(int, int)' "$work/methods.txt"
grep -q 'static native long calcOverload(long, long)' "$work/methods.txt"
grep -q 'static native double calcDouble(double, double, int)' "$work/methods.txt"
grep -q 'static native int calcLambda(int)' "$work/methods.txt"
grep -q 'static native int calcArray(int\[\], int)' "$work/methods.txt"
grep -q 'static int calcThrow()' "$work/methods.txt"

printf 'existing output' > "$work/rejected.jar"
if "$tool" protect --input "$work/original.jar" --output "$work/rejected.jar" --include 'Arithmetic#calc*' \
    > "$work/rejected.jsonl" 2> "$work/rejected.err"; then
    echo "Unsupported method unexpectedly succeeded under the fail policy" >&2
    exit 1
fi
[[ $(cat "$work/rejected.jar") == 'existing output' ]]
grep -q 'require JVM bytecode' "$work/rejected.err"

python3 - "$work" <<'PY'
import json, pathlib, struct, sys, warnings, zipfile

work = pathlib.Path(sys.argv[1])
decisions = [json.loads(line) for line in (work / "report.jsonl").read_text().splitlines()]
assert len([decision for decision in decisions if decision["result"] == "selected"]) == 20, decisions
assert {decision["method"].split("(")[0] for decision in decisions if decision["result"] == "retained"} == {
    "Arithmetic#calcThrow"
}, decisions
with zipfile.ZipFile(work / "original.jar") as original, zipfile.ZipFile(work / "protected.jar") as protected:
    assert b"Arithmetic.java" in original.read("Arithmetic$Digest.class")
    for name in protected.namelist():
        if name.endswith(".class"):
            data = protected.read(name)
            for label in (b"SourceFile", b"LineNumberTable", b"Arithmetic.java", b"NativeRuntime.java"):
                assert label not in data, (name, label)
    resources = [name for name in protected.namelist() if name.endswith((".so", ".dll"))]
    assert resources
    for name in resources:
        binary = protected.read(name)
        assert b"calcOverload\0" not in binary and b"calcDouble\0" not in binary, name
        if name.endswith(".so"):
            section_table, = struct.unpack_from("<Q", binary, 40)
            stride, sections = struct.unpack_from("<HH", binary, 58)
            types = {struct.unpack_from("<I", binary, section_table + i * stride + 4)[0]
                     for i in range(sections)}
            assert 2 not in types and 11 in types, name  # SHT_SYMTAB and SHT_DYNSYM
        else:
            assert b"protected.pdb" not in binary, name
    with zipfile.ZipFile(work / "tampered.jar", "w") as tampered:
        for name in protected.namelist():
            data = protected.read(name)
            tampered.writestr(name, data[:-1] + bytes([data[-1] ^ 1]) if name in resources else data)
    registration, = [name for name in protected.namelist() if "/registration/" in name and name.endswith(".bin")]
    with zipfile.ZipFile(work / "registration-tampered.jar", "w") as tampered:
        for name in protected.namelist():
            data = protected.read(name)
            tampered.writestr(name, data[:2] + bytes([data[2] ^ 1]) + data[3:] if name == registration else data)
for filename, entries in {
    "duplicate": [("same", b"first"), ("same", b"second")],
    "traversal": [("../outside", b"bad")],
    "signed": [("META-INF/SIGNER.SF", b"signature")],
    "malformed": [("Arithmetic.class", b"invalid class")],
}.items():
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", UserWarning)
        with zipfile.ZipFile(work / (filename + ".jar"), "w") as archive:
            for name, data in entries:
                archive.writestr(name, data)
PY
if "$java_home/bin/java" --enable-native-access=ALL-UNNAMED -jar "$work/tampered.jar" 2> "$work/tampered.err"; then
    echo "Tampered native library unexpectedly loaded" >&2
    exit 1
fi
grep -q 'Native library checksum mismatch' "$work/tampered.err"
if "$java_home/bin/java" -Xcheck:jni --enable-native-access=ALL-UNNAMED \
    -jar "$work/registration-tampered.jar" 2> "$work/registration-tampered.err"; then
    echo "Tampered registration metadata unexpectedly loaded" >&2
    exit 1
fi
grep -q 'Native method registration failed' "$work/registration-tampered.err"

for invalid in duplicate traversal signed malformed; do
    if "$tool" analyze --input "$work/$invalid.jar" --include 'Arithmetic#calc*' > /dev/null 2> "$work/$invalid.err"; then
        echo "Invalid input unexpectedly accepted: $invalid" >&2
        exit 1
    fi
done
grep -q 'Duplicate JAR entry' "$work/duplicate.err"
grep -q 'Unsafe JAR entry name' "$work/traversal.err"
grep -q 'Signed input JAR' "$work/signed.err"
grep -q 'classfile' "$work/malformed.err"
if (( $# == 0 )); then
    "$tool" protect --input "$work/original.jar" --output "$work/filtered.jar" \
        --include 'Arithmetic#calcInt(III)I' --include 'Arithmetic#calcFloatToInt(F)I' \
        --min-instructions 8 --report-native-sizes > "$work/filtered.jsonl"
    "$java_home/bin/java" -Xverify:all -Xcheck:jni --enable-native-access=ALL-UNNAMED \
        -jar "$work/filtered.jar" > "$work/filtered.txt"
    cmp "$work/original.txt" "$work/filtered.txt"
    "$tool" protect --input "$work/original.jar" --output "$work/translated.c" --include 'Arithmetic#calc*' \
        --unsupported retain --emit-c > "$work/emit-c.jsonl"
    "$tool" protect --input "$work/original.jar" --output "$work/padded.jar" --include 'Arithmetic#calc*' \
        --unsupported retain --padding on > "$work/padded.jsonl"
    "$java_home/bin/java" -Xverify:all -Xcheck:jni --enable-native-access=ALL-UNNAMED \
        -jar "$work/padded.jar" > "$work/padded.txt"
    cmp "$work/protected.txt" "$work/padded.txt"
    python3 - "$work" <<'PYTHON'
import json, pathlib, re, sys, zipfile

work = pathlib.Path(sys.argv[1])
filtered = [json.loads(line) for line in (work / "filtered.jsonl").read_text().splitlines()]
selected, skipped, size = (next(row for row in filtered if row["result"] == result)
                           for result in ("selected", "excluded", "native-size"))
assert len(filtered) == 3 and selected["method"] == size["method"] == "Arithmetic#calcInt(III)I", filtered
assert selected["instructions"] >= 8 and "branches" in selected, selected
assert skipped["method"] == "Arithmetic#calcFloatToInt(F)I" and skipped["instructions"] < 8, skipped
assert size["target"].endswith("-linux") and size["nativeBytes"] > 0, size
native_names = re.findall(r"^static \w+ JNICALL (method_[0-9a-f]{293})\(", (work / "translated.c").read_text(), re.M)
assert len(set(native_names)) == len(native_names) == 20, native_names
with zipfile.ZipFile(work / "original.jar") as original, zipfile.ZipFile(work / "padded.jar") as padded:
    original_classes = {name for name in original.namelist() if name.endswith(".class")}
    added_classes = {name for name in padded.namelist() if name.endswith(".class")} - original_classes
    runtime = {name for name in added_classes if re.fullmatch(r"dev/nativeobfuscator/runtime/Runtime[0-9a-f]{32}\.class", name)}
    generated = added_classes - runtime
    assert len(runtime) == 1 and len(generated) == 12, added_classes
    assert not any("/padding/" in name for name in padded.namelist())
    for entry in generated:
        assert not entry.startswith("META-INF/") and all(len(part) <= 255 for part in entry.split("/")), entry
        assert padded.read(entry).startswith(bytes.fromhex("cafebabe")), entry
    report = [json.loads(line) for line in (work / "padded.jsonl").read_text().splitlines()]
    converted = {row["method"].split("#")[0] + ".class" for row in report if row["result"] == "selected"}
    assert generated <= converted, (generated, converted)
PYTHON
fi
printf 'Java %s differential test passed (%s)\n' "$release" "$(cat "$work/protected.txt")"
