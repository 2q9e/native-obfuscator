#!/usr/bin/env bash
set -euo pipefail

plugin=${1:?usage: bash verify-cross.sh PLUGIN LLVM_BIN_DIR [PASS]}
llvm_bin=${2:?usage: bash verify-cross.sh PLUGIN LLVM_BIN_DIR [PASS]}
pass=${3:-native-encode}
case "$pass" in
  native-encode|native-diversify) ;;
  *) echo "Unsupported test pass: $pass" >&2; exit 1 ;;
esac
fixture_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
work_dir=$(mktemp -d)
trap 'rm -rf -- "$work_dir"' EXIT

printf '%-24s %13s %13s\n' 'Target' 'Baseline .text' 'Encoded .text'
for target in aarch64-linux-gnu x86_64-linux-gnu aarch64-windows-gnu x86_64-windows-gnu; do
  "$llvm_bin/clang" --target="$target" -ffreestanding -O2 -emit-llvm -c \
    "$fixture_dir/selected.c" -o "$work_dir/baseline.bc"
  "$llvm_bin/opt" -load-pass-plugin="$plugin" -verify-each \
    -passes="$pass<seed=42;max=1;prefix=method_>,verify" \
    "$work_dir/baseline.bc" -o "$work_dir/encoded.bc"
  for variant in baseline encoded; do
    "$llvm_bin/llc" -O=2 -filetype=obj -relocation-model=pic \
      "$work_dir/$variant.bc" -o "$work_dir/$variant.o"
    "$llvm_bin/llvm-objdump" -d --disassemble-symbols=method_1 \
      "$work_dir/$variant.o" >"$work_dir/$variant.asm"
  done

  case "$target" in
    aarch64-*)
      multiply='[[:space:]](mul|madd|msub)[[:space:]]'
      branch='[[:space:]]((t|c)bn?z|b\.[a-z]{2})[[:space:]]'
      ;;
    x86_64-*)
      multiply='[[:space:]]imul[lq]?[[:space:]]'
      branch='[[:space:]]j(e|ne|z|nz)[[:space:]]'
      ;;
  esac
  if grep -Eq "$multiply" "$work_dir/baseline.asm"; then
    echo "Unexpected multiplication in baseline for $target." >&2
    exit 1
  fi
  if ! grep -Eq "$multiply" "$work_dir/encoded.asm"; then
    echo "Encoded multiplication missing from object for $target." >&2
    exit 1
  fi
  if [[ "$pass" == native-diversify ]] && ! grep -Eq "$branch" "$work_dir/encoded.asm"; then
    echo "Diversified conditional branch missing from object for $target." >&2
    exit 1
  fi

  baseline=$("$llvm_bin/llvm-size" -A "$work_dir/baseline.o" | awk '$1==".text" {print $2}')
  encoded=$("$llvm_bin/llvm-size" -A "$work_dir/encoded.o" | awk '$1==".text" {print $2}')
  if [[ ! "$baseline" =~ ^[0-9]+$ || ! "$encoded" =~ ^[0-9]+$ ]]; then
    echo "Could not measure .text size for $target." >&2
    exit 1
  fi
  printf '%-24s %13d %13d\n' "$target" "$baseline" "$encoded"
done
