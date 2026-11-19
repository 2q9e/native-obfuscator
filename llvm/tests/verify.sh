#!/usr/bin/env bash
set -euo pipefail

plugin=${1:?usage: bash verify.sh PLUGIN LLVM_BIN_DIR [HOST_CC]}
llvm_bin=${2:?usage: bash verify.sh PLUGIN LLVM_BIN_DIR [HOST_CC]}
host_cc=${3:-cc}
fixture_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
work_dir=$(mktemp -d)
trap 'rm -rf -- "$work_dir"' EXIT

opt="$llvm_bin/opt"
llc="$llvm_bin/llc"
objdump="$llvm_bin/llvm-objdump"
"$opt" -passes='default<O2>,verify' "$fixture_dir/methods.ll" -o "$work_dir/baseline.bc"

encode() {
  "$opt" -load-pass-plugin="$plugin" -verify-each \
    -passes="native-encode<$1>,verify" "$work_dir/baseline.bc" -o "$2"
}

encode 'seed=42;max=1;prefix=method_' "$work_dir/encoded.bc"
encode 'seed=42;max=1;prefix=method_' "$work_dir/repeat.bc"
encode 'seed=43;max=1;prefix=method_' "$work_dir/different.bc"
encode 'seed=42;max=0;prefix=method_' "$work_dir/disabled.bc"
"$opt" -passes=verify "$work_dir/baseline.bc" -o "$work_dir/verified.bc"
cmp "$work_dir/encoded.bc" "$work_dir/repeat.bc"
cmp "$work_dir/disabled.bc" "$work_dir/verified.bc"
if cmp -s "$work_dir/encoded.bc" "$work_dir/different.bc"; then
  echo 'Different seeds generated the same bitcode.' >&2
  exit 1
fi

for options in 'max=-1' 'seed=18446744073709551616' 'max=1;max=2' \
  'prefix=' 'unknown=1'; do
  if encode "$options" "$work_dir/invalid.bc" 2>"$work_dir/invalid.log"; then
    echo "Invalid options accepted: $options" >&2
    exit 1
  fi
  grep -q 'invalid native-encode options' "$work_dir/invalid.log"
done

for variant in baseline encoded different; do
  "$opt" -S -passes=verify "$work_dir/$variant.bc" -o "$work_dir/$variant.ll"
  "$llc" -filetype=obj -relocation-model=pic "$work_dir/$variant.bc" -o "$work_dir/$variant.o"
  "$host_cc" -O2 "$fixture_dir/differential.c" "$work_dir/$variant.o" -o "$work_dir/$variant"
  "$work_dir/$variant"
done

body() {
  sed -n "/^define .* @$1(/,/^}/p" "$2"
}

for name in method_flags32 unselected_sum32 method_optnone32; do
  diff -u <(body "$name" "$work_dir/baseline.ll") \
    <(body "$name" "$work_dir/encoded.ll")
done
for width in 8 16 32 64; do
  test "$(grep -c " mul i$width " "$work_dir/encoded.ll")" -ge 3
done
test "$(body method_chain32 "$work_dir/encoded.ll" | grep -c ' mul i32 ')" -eq 3
test "$(body method_chain32 "$work_dir/encoded.ll" | grep -c ' sub i32 ')" -eq 1

case "$(uname -m)" in
  aarch64|arm64) multiply='[[:space:]](mul|madd|msub)[[:space:]]' ;;
  x86_64|amd64) multiply='[[:space:]]imul[lq]?[[:space:]]' ;;
  *)
    echo 'Machine-code multiplication check is unavailable on this host architecture.'
    exit 0
    ;;
esac
for variant in baseline encoded; do
  "$objdump" -d --disassemble-symbols=method_sum32 "$work_dir/$variant" \
    >"$work_dir/$variant.asm"
done
if grep -Eq "$multiply" "$work_dir/baseline.asm"; then
  echo 'Unexpected multiplication in the baseline machine code.' >&2
  exit 1
fi
grep -E "$multiply" "$work_dir/encoded.asm"
echo 'Encoded arithmetic survived the native back end in method_sum32.'
