#!/usr/bin/env bash
set -euo pipefail

plugin=${1:?usage: bash verify.sh PLUGIN LLVM_BIN_DIR [HOST_CC] [PASS]}
llvm_bin=${2:?usage: bash verify.sh PLUGIN LLVM_BIN_DIR [HOST_CC] [PASS]}
host_cc=${3:-cc}
pass=${4:-native-encode}
case "$pass" in
  native-encode|native-diversify) ;;
  *) echo "Unsupported test pass: $pass" >&2; exit 1 ;;
esac
fixture_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
work_dir=$(mktemp -d)
trap 'rm -rf -- "$work_dir"' EXIT

opt="$llvm_bin/opt"
llc="$llvm_bin/llc"
objdump="$llvm_bin/llvm-objdump"
"$opt" -passes='default<O2>,verify' "$fixture_dir/methods.ll" -o "$work_dir/baseline.bc"

run_pass() {
  "$opt" -load-pass-plugin="$plugin" -verify-each \
    -passes="$pass<$1>,verify" "$work_dir/baseline.bc" -o "$2"
}

run_pass 'seed=42;max=1;prefix=method_' "$work_dir/encoded.bc"
run_pass 'seed=42;max=1;prefix=method_' "$work_dir/repeat.bc"
run_pass 'seed=43;max=1;prefix=method_' "$work_dir/different.bc"
run_pass 'seed=42;max=0;prefix=method_' "$work_dir/disabled.bc"
"$opt" -passes=verify "$work_dir/baseline.bc" -o "$work_dir/verified.bc"
cmp "$work_dir/encoded.bc" "$work_dir/repeat.bc"
cmp "$work_dir/disabled.bc" "$work_dir/verified.bc"
if cmp -s "$work_dir/encoded.bc" "$work_dir/different.bc"; then
  echo 'Different seeds generated the same bitcode.' >&2
  exit 1
fi

for options in 'max=-1' 'seed=18446744073709551616' 'max=1;max=2' \
  'prefix=' 'unknown=1'; do
  if run_pass "$options" "$work_dir/invalid.bc" 2>"$work_dir/invalid.log"; then
    echo "Invalid options accepted: $options" >&2
    exit 1
  fi
  grep -q "invalid $pass options" "$work_dir/invalid.log"
done

variants=(baseline encoded different)
untouched=(method_flags32 unselected_sum32 method_optnone32)
if [[ "$pass" == native-diversify ]]; then
  untouched+=(method_unguarded32)
  # Keep the original PHI edges and convergent attribute for the CFG tests.
  "$opt" -S -passes=verify "$fixture_dir/methods.ll" -o "$work_dir/original.ll"
  "$opt" -load-pass-plugin="$plugin" -verify-each \
    -passes='native-diversify<seed=42;max=2;prefix=method_>,verify' \
    "$fixture_dir/methods.ll" -o "$work_dir/original-phi.bc"
  variants+=(original-phi)
fi
for variant in "${variants[@]}"; do
  "$opt" -S -passes=verify "$work_dir/$variant.bc" -o "$work_dir/$variant.ll"
  "$llc" -filetype=obj -relocation-model=pic "$work_dir/$variant.bc" -o "$work_dir/$variant.o"
  "$host_cc" -O2 "$fixture_dir/differential.c" "$work_dir/$variant.o" -o "$work_dir/$variant"
  "$work_dir/$variant"
done

body() {
  sed -n "/^define .* @$1(/,/^}/p" "$2"
}

for name in "${untouched[@]}"; do
  diff -u <(body "$name" "$work_dir/baseline.ll") \
    <(body "$name" "$work_dir/encoded.ll")
done
if [[ "$pass" == native-diversify ]]; then
  diff -u <(body method_convergent32 "$work_dir/original.ll") \
    <(body method_convergent32 "$work_dir/original-phi.ll")
  test "$(body method_chain32 "$work_dir/encoded.ll" | grep -c ' br i1 ')" -eq 1
  test "$(body method_phi32 "$work_dir/original-phi.ll" | grep -c ' phi i32 ')" -eq 3
  test "$(body method_phi32 "$work_dir/original-phi.ll" | grep -c ' br i1 ')" -eq 3
else
  for width in 8 16 32 64; do
    test "$(grep -c " mul i$width " "$work_dir/encoded.ll")" -ge 3
  done
  test "$(body method_chain32 "$work_dir/encoded.ll" | grep -c ' sub i32 ')" -eq 1
fi
test "$(body method_chain32 "$work_dir/encoded.ll" | grep -c ' mul i32 ')" -eq 3

case "$(uname -m)" in
  aarch64|arm64)
    multiply='[[:space:]](mul|madd|msub)[[:space:]]'
    branch='[[:space:]]((t|c)bn?z|b\.[a-z]{2})[[:space:]]'
    ;;
  x86_64|amd64)
    multiply='[[:space:]]imul[lq]?[[:space:]]'
    branch='[[:space:]]j(e|ne|z|nz)[[:space:]]'
    ;;
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
if [[ "$pass" == native-diversify ]]; then
  grep -E "$branch" "$work_dir/encoded.asm"
fi
echo 'Encoded arithmetic survived the native back end in method_sum32.'
