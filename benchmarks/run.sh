#!/usr/bin/env bash
set -euo pipefail

if (( $# < 1 )); then
    printf 'Usage: bash benchmarks/run.sh /path/to/native-obfuscator [LLVM protect arguments]\n' >&2
    exit 2
fi
tool=$1
shift
java_home=${JAVA_HOME:?Set JAVA_HOME to a JDK}
source_dir=$(dirname -- "${BASH_SOURCE[0]}")
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
mkdir "$work/classes"
"$java_home/bin/javac" --release 21 -d "$work/classes" "$source_dir/Workload.java"
"$java_home/bin/jar" --create --file "$work/java.jar" --main-class Workload -C "$work/classes" .
build() {
    local name=$1
    shift
    local start=$SECONDS
    "$tool" protect --input "$work/java.jar" --output "$work/$name.jar" \
        --include 'Workload#boundary(I)I' --include 'Workload#mix(II)I' "$@" > /dev/null
    printf '%s build seconds (shell resolution): %d\n' "$name" "$((SECONDS - start))" >&2
}
build native
variants=(java native)
if (( $# )); then
    build llvm "$@"
    variants+=(llvm)
fi
for mode in "${variants[@]}"; do
    "$java_home/bin/java" --enable-native-access=ALL-UNNAMED -jar "$work/$mode.jar" > "$work/$mode.txt"
done
python3 - "$work" "${variants[@]}" <<'PY'
import json, os, pathlib, sys, zipfile

root = pathlib.Path(sys.argv[1])
baseline = {}
for variant in sys.argv[2:]:
    metrics = {}
    for line in (root / f"{variant}.txt").read_text().splitlines():
        workload, ns, checksum = line.split()
        if variant == "java":
            baseline[workload] = (float(ns), checksum)
        else:
            assert checksum == baseline[workload][1], (workload, variant)
        metrics[workload] = {"median_ns_per_call": float(ns), "checksum": checksum}
        if variant != "java" and baseline[workload][0] > 0:
            metrics[workload]["overhead_percent"] = round((float(ns) / baseline[workload][0] - 1) * 100, 2)
    jar = root / f"{variant}.jar"
    with zipfile.ZipFile(jar) as archive:
        native = {info.filename.split("/")[-2]: info.file_size for info in archive.infolist()
                  if info.filename.endswith((".so", ".dll"))}
    print(json.dumps({"variant": variant, "jar_bytes": jar.stat().st_size, "native_bytes": native, "workloads": metrics}))
    if variant == "llvm" and "MAX_ARITHMETIC_OVERHEAD_PERCENT" in os.environ:
        if metrics["arithmetic"]["overhead_percent"] > float(os.environ["MAX_ARITHMETIC_OVERHEAD_PERCENT"]):
            raise SystemExit("LLVM arithmetic workload exceeded the requested runtime budget")
PY
