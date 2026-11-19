#!/usr/bin/env bash
set -euo pipefail
launcher=${1:?Pass the Gradle distribution launcher path}
java_home=${JAVA_HOME:?Set JAVA_HOME to the JDK under test}
release=${JAVA_RELEASE:-21}
dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
asm=${ASM_CLASSPATH:-"$(dirname -- "$launcher")/../lib/asm-9.9.jar"}
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
mkdir "$work/generator" "$work/classes"
"$java_home/bin/javac" --release "$release" -cp "$asm" -d "$work/generator" "$dir/GenerateKernels.java"
"$java_home/bin/javac" --release "$release" -d "$work/classes" "$dir/CheckKernels.java"
for seed in 12345 90475; do
    "$java_home/bin/java" -cp "$work/generator:$asm" GenerateKernels "$work/classes" "$release" "$seed"
    "$java_home/bin/jar" --create --file "$work/original.jar" --main-class CheckKernels -C "$work/classes" .
    "$java_home/bin/java" -Xverify:all -jar "$work/original.jar" java > "$work/baseline.txt"
    for flavor in protected replicated; do
        "$launcher" protect --input "$work/original.jar" --output "$work/$flavor.jar" \
            --include 'GeneratedKernels#calc*' > "$work/$flavor.jsonl"
    done
    cmp "$work/protected.jar" "$work/replicated.jar"
    "$java_home/bin/java" -Xverify:all -Xcheck:jni --enable-native-access=ALL-UNNAMED \
        -jar "$work/protected.jar" native > "$work/native.txt"
    cmp "$work/baseline.txt" "$work/native.txt"
    printf 'Java %s seed %s: verified 60,000 calls and deterministic JAR (%s)\n' \
        "$release" "$seed" "$(cat "$work/native.txt")"
done
