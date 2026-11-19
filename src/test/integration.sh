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
grep -q 'static int calcLambda(int)' "$work/methods.txt"
grep -q 'static int calcArray(int\[\], int)' "$work/methods.txt"
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
import json, pathlib, sys, warnings, zipfile

work = pathlib.Path(sys.argv[1])
decisions = [json.loads(line) for line in (work / "report.jsonl").read_text().splitlines()]
assert len([decision for decision in decisions if decision["result"] == "selected"]) == 18, decisions
assert {decision["method"].split("(")[0] for decision in decisions if decision["result"] == "retained"} == {
    "Arithmetic#calcLambda", "Arithmetic#calcArray", "Arithmetic#calcThrow"
}, decisions
with zipfile.ZipFile(work / "original.jar") as original, zipfile.ZipFile(work / "protected.jar") as protected:
    assert original.read("Arithmetic$Digest.class") == protected.read("Arithmetic$Digest.class")
    resources = [name for name in protected.namelist() if name.endswith((".so", ".dll"))]
    assert resources
    with zipfile.ZipFile(work / "tampered.jar", "w") as tampered:
        for name in protected.namelist():
            data = protected.read(name)
            tampered.writestr(name, data[:-1] + bytes([data[-1] ^ 1]) if name in resources else data)
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
    "$tool" protect --input "$work/original.jar" --output "$work/padded.jar" --include 'Arithmetic#calc*' \
        --unsupported retain --padding on > "$work/padded.jsonl"
    "$java_home/bin/java" -Xverify:all -Xcheck:jni --enable-native-access=ALL-UNNAMED \
        -jar "$work/padded.jar" > "$work/padded.txt"
    cmp "$work/protected.txt" "$work/padded.txt"
    python3 - "$work" <<'PYTHON'
import pathlib, re, sys, zipfile

work = pathlib.Path(sys.argv[1])
with zipfile.ZipFile(work / "protected.jar") as standard, zipfile.ZipFile(work / "padded.jar") as padded:
    new_entries = set(padded.namelist()) - set(standard.namelist())
    assert len(new_entries) == 12, new_entries
    for unchanged in standard.namelist():
        assert standard.read(unchanged) == padded.read(unchanged), unchanged
    for entry in new_entries:
        assert re.fullmatch(
            r"META-INF/native-obfuscator/Runtime[0-9a-f]{32}/padding/dev/nativeobfuscator/padding/C[0-9a-f]{24}\.class",
            entry,
        ), entry
        bytecode = padded.read(entry)
        assert bytecode.startswith(bytes.fromhex("cafebabe")), entry
        assert b"SourceFile" not in bytecode, entry
PYTHON
fi
printf 'Java %s differential test passed (%s)\n' "$release" "$(cat "$work/protected.txt")"
