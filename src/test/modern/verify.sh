#!/usr/bin/env bash
set -euo pipefail

if (( $# < 1 )); then
    echo "Usage: JAVA_HOME=/jdk JAVA_RELEASE=21 bash src/test/modern/verify.sh /path/to/native-obfuscator [protect compiler arguments]" >&2
    echo "Set ASM_CLASSPATH if the launcher is outside the Gradle distribution." >&2
    exit 2
fi

tool=$1
shift
java_home=${JAVA_HOME:?Set JAVA_HOME to the JDK under test}
release=${JAVA_RELEASE:-21}
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
asm_classpath=${ASM_CLASSPATH:-}
if [[ -z "$asm_classpath" ]]; then
    shopt -s nullglob
    asm_jars=("$(dirname -- "$tool")"/../lib/asm-[0-9]*.jar)
    shopt -u nullglob
    if (( ${#asm_jars[@]} != 1 )); then
        echo "Expected one ASM core JAR in the launcher distribution. Set ASM_CLASSPATH explicitly." >&2
        exit 2
    fi
    asm_classpath=${asm_jars[0]}
fi

work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
mkdir "$work/generator" "$work/classes"

"$java_home/bin/javac" --release 21 -encoding UTF-8 -Xlint:all -Werror -cp "$asm_classpath" \
    -d "$work/generator" "$script_dir/GenerateCondy.java"
"$java_home/bin/java" -cp "$work/generator:$asm_classpath" GenerateCondy "$release" "$work/classes"
"$java_home/bin/javac" --release "$release" -encoding UTF-8 -Xlint:all -Werror \
    -d "$work/classes" "$script_dir/VerifyModern.java"
"$java_home/bin/jar" --create --file "$work/original.jar" --main-class modern.VerifyModern -C "$work/classes" .
"$java_home/bin/java" -Xverify:all -jar "$work/original.jar" original > "$work/original.txt"

selection=(
    --include 'modern/CondyFixture#calc*'
    --include 'modern/VerifyModern$BridgeFixture#calcRecord*'
    --include 'modern/VerifyModern$BridgeFixture#get*'
)
"$tool" analyze --input "$work/original.jar" "${selection[@]}" --unsupported retain > "$work/analysis.jsonl"
if "$tool" analyze --input "$work/original.jar" "${selection[@]}" > "$work/rejected.jsonl" 2> "$work/rejected.err"; then
    echo "Default unsupported policy unexpectedly accepted the fixture" >&2
    exit 1
fi
grep -q 'require JVM bytecode' "$work/rejected.err"
"$tool" protect --input "$work/original.jar" --output "$work/protected.jar" \
    "${selection[@]}" --unsupported retain "$@" > "$work/protection.jsonl"
"$java_home/bin/java" -Xverify:all -Xcheck:jni --enable-native-access=ALL-UNNAMED \
    -jar "$work/protected.jar" protected > "$work/protected.txt"
cmp "$work/original.txt" "$work/protected.txt"

python3 - "$work" <<'PY'
import json
import pathlib
import sys

work = pathlib.Path(sys.argv[1])
condy = "modern/CondyFixture#calcCondy()I"
unicode = "modern/CondyFixture#calc\u03bb\U00010400(IJLmodern/Marker_\U00010400;)J"
record = "modern/VerifyModern$BridgeFixture#calcRecord(I)I"
bridges = {
    "modern/VerifyModern$BridgeFixture#get()Ljava/lang/Integer;",
    "modern/VerifyModern$BridgeFixture#get()Ljava/lang/Object;",
}
for report, supported, unsupported in (
    ("analysis", "eligible", "retained"),
    ("rejected", "eligible", "rejected"),
    ("protection", "selected", "retained"),
):
    decisions = [json.loads(line) for line in (work / f"{report}.jsonl").read_text(encoding="utf-8").splitlines()]
    expected = {unicode: supported, record: supported, condy: unsupported}
    expected.update(dict.fromkeys(bridges, unsupported))
    assert len(decisions) == len(expected), decisions
    assert {decision["method"]: decision["result"] for decision in decisions} == expected, decisions
    reasons = {decision["method"]: decision["reason"] for decision in decisions}
    assert "constant requires JVM linkage" in reasons[condy], decisions
    assert all("reference-return" in reasons[method] for method in bridges), decisions
PY
printf 'Java %s modern bytecode test passed (%s)\n' "$release" "$(cat "$work/protected.txt")"
