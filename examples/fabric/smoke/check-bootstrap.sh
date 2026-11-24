#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 3 ]; then
    printf 'Usage: %s PROTECTED.jar MOJANG_SERVER.jar FABRIC_CLASSPATH\n' "$0" >&2
    exit 2
fi

fixture_jar="$(realpath "$1")"
mojang_jar="$(realpath "$2")"
fixture_source="$(cd -- "$(dirname -- "$0")" && pwd)"
fixture_jdk="${JAVA_HOME:?Set JAVA_HOME to JDK 21 or 25}"
fixture_run="$(mktemp -d)"
trap 'rm -rf -- "$fixture_run"' EXIT

declarations=$("$fixture_jdk/bin/javap" -p -classpath "$fixture_jar" example.nativefixture.Kernel)
if ! grep -F 'static native int mix(int, int);' <<< "$declarations" > /dev/null ||
    ! grep -F 'static native int identifierScore(net.minecraft.class_2960, int);' <<< "$declarations" > /dev/null ||
    ! grep -F 'static native int rankRegistryCandidates(net.minecraft.class_2960[], int);' <<< "$declarations" > /dev/null ||
    ! grep -F 'static native java.util.function.ToIntFunction<net.minecraft.class_2960> registryScorer(int, net.minecraft.class_2960);' \
        <<< "$declarations" > /dev/null ||
    ! grep -F 'static native int lambda$registryScorer$0(' <<< "$declarations" > /dev/null ||
    ! grep -F 'static native int identifierPriorityOrDefault(net.minecraft.class_2960, net.minecraft.class_2960, int, int);' \
        <<< "$declarations" > /dev/null; then
    printf 'Expected native mix, score, fallback, registry ranking, and callback methods in the remapped Kernel class.\n' >&2
    exit 1
fi
if [ "${NATIVE_FIXTURE_EXPECT_HIERARCHY:-0}" = 1 ] &&
    ! grep -F 'static native int identifierPriority(net.minecraft.class_2960, net.minecraft.class_2960, int);' \
        <<< "$declarations" > /dev/null; then
    printf 'Expected a native dependency-typed Identifier method.\n' >&2
    exit 1
fi

mkdir -p "$fixture_run/mods"
cp -- "$fixture_jar" "$fixture_run/mods/native-fabric-fixture.jar"
cd -- "$fixture_run"
fabric_cp="$mojang_jar:$3"

timeout 60s "$fixture_jdk/bin/java" --enable-native-access=ALL-UNNAMED -cp "$fabric_cp" \
    net.fabricmc.loader.impl.launch.knot.KnotServer --help > server-help.log 2>&1 || {
    cat server-help.log >&2
    exit 1
}
if ! grep -F 'Loading Minecraft 1.21.11 with Fabric Loader 0.18.4' server-help.log ||
    ! grep -F 'native_fixture 1.0.0' server-help.log ||
    ! grep -F -- '--initSettings' server-help.log; then
    cat server-help.log >&2
    exit 1
fi

"$fixture_jdk/bin/javac" -proc:none --release 21 -cp "$fabric_cp" \
    -d "$fixture_run" "$fixture_source/BootstrapFixture.java"
timeout 60s "$fixture_jdk/bin/java" --enable-native-access=ALL-UNNAMED -Xverify:all -Xcheck:jni \
    -cp "$fixture_run:$fabric_cp" BootstrapFixture > bootstrap.log 2>&1 || {
    cat bootstrap.log >&2
    exit 1
}
if ! grep -F 'Native fixture passed (kernel = 997305193, identifier score = 28443952, priority = 1030, fallback = -1, registry = 1115680, callback = -28507579, Mixin and access widener active)' bootstrap.log ||
    grep -Eq 'WARNING in native method|FATAL ERROR in native method' bootstrap.log; then
    cat bootstrap.log >&2
    exit 1
fi
test ! -e eula.txt
