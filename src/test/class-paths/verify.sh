#!/usr/bin/env bash
set -euo pipefail
tool=$1
shift
jdk=${JAVA_HOME:?Set JAVA_HOME to the JDK under test}/bin
here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
mkdir "$work/classes"
"$jdk/javac" --release 21 -g -parameters -d "$work/classes" "$here/Launcher.java" "$here/HiddenLogic.java"
"$jdk/jar" --create --file "$work/input.jar" --main-class fixture.Launcher -C "$work/classes" .
"$jdk/java" -Xverify:all -jar "$work/input.jar" > "$work/expected"

for package in q7 sensitive/engine; do
    cat > "$work/class.map" <<MAP
sensitive/engine/HiddenLogic=$package/A8
sensitive/engine/HiddenLogic\$Member=$package/A8\$C6
sensitive/engine/HiddenLogic\$1Local=$package/A8\$1D4
sensitive/engine/HiddenLogic\$1=$package/A8\$1
MAP
    common=(--input "$work/input.jar" --class-map "$work/class.map" --include "$package/A8#calculate*"
        --include "$package/A8#nativeLookup*" --padding off)
    "$tool" harden "${common[@]}" --output "$work/staged.jar" "$@" > "$work/harden.jsonl"
    "$tool" verify "${common[@]}" --staged "$work/staged.jar" "$@" > "$work/verify.jsonl"
    "$tool" apply "${common[@]}" --staged "$work/staged.jar" --output "$work/released.jar" \
        --state-dir "$work/state" --confirm-overwrite "$@" > "$work/apply.jsonl"
    cmp "$work/staged.jar" "$work/released.jar"
    "$tool" restore --output "$work/released.jar" --state-dir "$work/state" > "$work/restore.jsonl"
    [[ ! -e "$work/released.jar" ]]
    "$jdk/java" -Xverify:all -Xcheck:jni --enable-native-access=ALL-UNNAMED -jar "$work/staged.jar" C6 D4 > "$work/actual"
    cmp "$work/expected" "$work/actual"
    python3 - "$work" "$package" <<'PY'
import json, pathlib, sys, zipfile

work, package = pathlib.Path(sys.argv[1]), sys.argv[2]
detail = json.loads((work / 'verify.jsonl').read_text().splitlines()[-1])['verification']
assert detail['structurallyValid'], detail
assert {f'{package}/A8#calculate(II)I', f'{package}/A8#nativeLookup(I)I'} <= set(detail['verifiedMethods']), detail
with zipfile.ZipFile(work / 'staged.jar') as jar:
    paths = jar.namelist()
    assert all(f'{package}/A8{suffix}.class' in paths for suffix in ('', '$1', '$1D4', '$C6')), paths
    assert package != 'q7' or not any(path.startswith('sensitive/') for path in paths), paths
    for path in paths:
        if path.endswith(('.class', '.so', '.dll', '.bin')):
            data = jar.read(path)
            assert b'sensitive/engine/HiddenLogic' not in data, path
            assert b'HiddenLogic.java' not in data, path
PY
done

printf 'Gradle compiler cache fixture' > "$work/classes/previous-compilation-data.bin"
printf 'runtime resource' > "$work/classes/resource.txt"
"$jdk/jar" --update --file "$work/input.jar" -C "$work/classes" previous-compilation-data.bin -C "$work/classes" resource.txt
cat > "$work/cleanup.map" <<'MAP'
sensitive/engine/HiddenLogic=q7/A8
sensitive/engine/HiddenLogic$Member=q7/A8$C6
sensitive/engine/HiddenLogic$1Local=q7/A8$1D4
sensitive/engine/HiddenLogic$1=q7/A8$1
MAP
"$tool" strip-metadata --input "$work/input.jar" --output "$work/cleaned.jar" --class-map "$work/cleanup.map"
"$jdk/java" -Xverify:all -jar "$work/cleaned.jar" C6 D4 > "$work/cleaned-output"
cmp "$work/expected" "$work/cleaned-output"
python3 - "$work/cleaned.jar" <<'PYTHON'
import sys
from zipfile import ZipFile

with ZipFile(sys.argv[1]) as jar:
    names = jar.namelist()
    assert 'previous-compilation-data.bin' not in names
    assert jar.read('resource.txt') == b'runtime resource'
    assert 'fixture/Launcher.class' in names
    assert 'q7/A8.class' in names
    for name in names:
        if name.endswith('.class'):
            data = jar.read(name)
            for label in (b'LineNumberTable', b'LocalVariableTable', b'SourceFile', b'Launcher.java', b'HiddenLogic.java'):
                assert label not in data, (name, label)
PYTHON
if "$tool" strip-metadata --input "$work/input.jar" --output "$work/input.jar" > "$work/rejected.log" 2>&1; then
    echo 'In-place cleanup unexpectedly succeeded' >&2
    exit 1
fi
grep -q 'Input and output must be different files' "$work/rejected.log"

printf 'sensitive/engine/HiddenLogic=sensitive/engine/A8\n' > "$work/incomplete.map"
if "$tool" analyze --input "$work/input.jar" --class-map "$work/incomplete.map" --include '*' > "$work/rejected.log" 2>&1; then
    echo 'A partial nest rename unexpectedly succeeded' >&2
    exit 1
fi
grep -q 'Nested class .* must map under' "$work/rejected.log"
printf 'fixture/Launcher=fixture/F\n' > "$work/entrypoint.map"
if "$tool" analyze --input "$work/input.jar" --class-map "$work/entrypoint.map" --include '*' > "$work/rejected.log" 2>&1; then
    echo 'Renaming a manifest entrypoint unexpectedly succeeded' >&2
    exit 1
fi
grep -q 'externally registered class: fixture/Launcher' "$work/rejected.log"

mkdir "$work/fabric"
printf '{"accessWidener":"example.accesswidener"}\n' > "$work/fabric/fabric.mod.json"
printf 'accessWidener v2 named\naccessible method fixture/Launcher describe ()Lsensitive/engine/HiddenLogic;\n' \
    > "$work/fabric/example.accesswidener"
"$jdk/jar" --update --file "$work/input.jar" -C "$work/fabric" .
if "$tool" analyze --input "$work/input.jar" --class-map "$work/class.map" --include '*' > "$work/rejected.log" 2>&1; then
    echo 'Renaming a class referenced by an access widener descriptor unexpectedly succeeded' >&2
    exit 1
fi
grep -q 'externally registered class: sensitive/engine/HiddenLogic' "$work/rejected.log"
printf 'Class path remapping and nested metadata passed (%s)\n' "$(cat "$work/actual")"
