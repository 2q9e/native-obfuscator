#!/usr/bin/env bash
set -euo pipefail

if (( $# < 1 )); then
    echo "Usage: JAVA_HOME=/jdk GRADLE_HOME=/gradle-9.3.1 bash src/test/kotlin/verify.sh /path/to/native-obfuscator [protect compiler arguments]" >&2
    exit 2
fi

tool=$1
shift
java_home=${JAVA_HOME:?Set JAVA_HOME to JDK 21 or newer}
gradle_home=${GRADLE_HOME:?Set GRADLE_HOME to the Gradle 9.3.1 binary distribution}
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
kotlin_version=2.2.21
compiler="$gradle_home/lib/kotlin-compiler-embeddable-$kotlin_version.jar"
stdlib="$gradle_home/lib/kotlin-stdlib-$kotlin_version.jar"
reflect="$gradle_home/lib/kotlin-reflect-$kotlin_version.jar"
for required in "$compiler" "$stdlib" "$reflect"; do
    if [[ ! -f "$required" ]]; then
        echo "Missing Kotlin $kotlin_version prerequisite: $required" >&2
        exit 2
    fi
done

work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
mkdir "$work/classes"
"$java_home/bin/java" -cp "$gradle_home/lib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -jvm-target 21 -Xlambdas=indy -no-stdlib -no-reflect -classpath "$stdlib:$reflect" \
    -module-name native_obfuscator_fixture -Werror -d "$work/classes" "$script_dir/KotlinFixture.kt"
"$java_home/bin/jar" --create --file "$work/original.jar" -C "$work/classes" .

main=fixture.kotlin.KotlinFixtureKt
"$java_home/bin/java" -Xverify:all -cp "$work/original.jar:$stdlib:$reflect" "$main" original > "$work/original.txt"
selection=(
    --include 'fixture/kotlin/KotlinFixture#calc*'
    --include 'fixture/kotlin/KotlinFixtureKt#calcStatic*'
)
"$tool" analyze --input "$work/original.jar" "${selection[@]}" --unsupported retain > "$work/analysis.jsonl"
"$tool" protect --input "$work/original.jar" --output "$work/protected.jar" \
    "${selection[@]}" --unsupported retain "$@" > "$work/protection.jsonl"
"$java_home/bin/java" -Xverify:all -Xcheck:jni --enable-native-access=ALL-UNNAMED \
    -cp "$work/protected.jar:$stdlib:$reflect" "$main" protected > "$work/protected.txt"
cmp "$work/original.txt" "$work/protected.txt"

python3 - "$work" <<'PY'
import json
import pathlib
import sys
import zipfile

work = pathlib.Path(sys.argv[1])
instance = "fixture/kotlin/KotlinFixture#"
lambda_method = instance + "calcLambda(I)I"
reference = instance + "calcCallableReference(I)I"
lambda_body = instance + "calcLambda$lambda$0(Lfixture/kotlin/KotlinFixture;II)I"
for report, supported in (("analysis", "eligible"), ("protection", "selected")):
    decisions = [
        json.loads(line)
        for line in (work / f"{report}.jsonl").read_text(encoding="utf-8").splitlines()
    ]
    expected = {
        instance + "calcPrimitive(II)I": supported,
        "fixture/kotlin/KotlinFixtureKt#calcStatic(JJI)J": supported,
        lambda_method: "retained",
        reference: "retained",
        lambda_body: "retained",
    }
    assert len(decisions) == len(expected), decisions
    assert {decision["method"]: decision["result"] for decision in decisions} == expected, decisions
    reasons = {decision["method"]: decision["reason"] for decision in decisions}
    assert "(opcode 186)" in reasons[lambda_method], decisions
    assert "(opcode 187)" in reasons[reference], decisions
    assert "(opcode 182)" in reasons[lambda_body], decisions
metadata = "META-INF/native_obfuscator_fixture.kotlin_module"
with zipfile.ZipFile(work / "original.jar") as original, zipfile.ZipFile(work / "protected.jar") as protected:
    assert original.read(metadata) == protected.read(metadata)
PY

printf 'Kotlin %s native differential test passed (%s)\n' "$kotlin_version" "$(cat "$work/protected.txt")"
