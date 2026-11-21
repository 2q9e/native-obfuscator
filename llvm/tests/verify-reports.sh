#!/usr/bin/env bash
set -euo pipefail

plugin=${1:?usage: bash verify-reports.sh PLUGIN LLVM_BIN_DIR}
llvm_bin=${2:?usage: bash verify-reports.sh PLUGIN LLVM_BIN_DIR}
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
cat > "$work/reports.ll" <<'IR'
define i32 @method_sum(i32 noundef %a, i32 noundef %b) {
  %result = add i32 %a, %b
  ret i32 %result
}
define i32 @method_identity(i32 noundef %a) {
  ret i32 %a
}
define i32 @unselected(i32 noundef %a, i32 noundef %b) {
  %result = add i32 %a, %b
  ret i32 %result
}
IR

for pass in native-encode native-diversify; do
  for cap in 0 1; do
    "$llvm_bin/opt" -load-pass-plugin="$plugin" -verify-each \
      "-passes=$pass<seed=42;max=$cap;prefix=method_>,verify" \
      "$work/reports.ll" -o "$work/output.bc" 2> "$work/reports.log"
    grep -q "native-obfuscator-function: pass=$pass function=method_sum candidates=1 eligible=1 transformed=$cap " "$work/reports.log"
    grep -q "native-obfuscator-function: pass=$pass function=method_identity candidates=0 eligible=0 transformed=0 " "$work/reports.log"
    test "$(grep -c '^native-obfuscator-function:' "$work/reports.log")" -eq 2
    grep -q "native-obfuscator: $pass candidates=1 eligible=1 transformed=$cap " "$work/reports.log"
  done
done
echo 'Per-function transformation reports include positive and zero-site methods and preserve totals.'
