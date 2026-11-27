#!/usr/bin/env bash
set -euo pipefail

plugin=${1:?usage: bash verify-reports.sh PLUGIN LLVM_BIN_DIR}
llvm_bin=${2:?usage: bash verify-reports.sh PLUGIN LLVM_BIN_DIR}
plugin="$(realpath "$plugin")"
llvm_bin="$(realpath "$llvm_bin")"
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
define i17 @method_narrow(i17 noundef %a, i17 noundef %b) {
  %result = add i17 %a, %b
  ret i17 %result
}
define i32 @method_flagged(i32 noundef %a, i32 noundef %b) {
  %result = add nsw i32 %a, %b
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
    grep -q 'function=method_narrow candidates=1 eligible=0 transformed=0 .*skipped_type=1' "$work/reports.log"
    grep -q 'function=method_flagged candidates=1 eligible=0 transformed=0 .*skipped_flags=1' "$work/reports.log"
    test "$(grep -c '^native-obfuscator-function:' "$work/reports.log")" -eq 4
    grep -q "native-obfuscator: $pass candidates=3 eligible=1 transformed=$cap " "$work/reports.log"
  done
done

(
  cd "$work"
  printf 'method_sum\nunselected\n' > llvm-functions.txt
  for selection in 'functions=method_sum+unselected' 'functions-file=llvm-functions.txt'; do
    "$llvm_bin/opt" -load-pass-plugin="$plugin" -verify-each \
      "-passes=native-encode<prefix=method_;$selection>" reports.ll -o scoped.bc 2> scoped.log
    test "$(grep -c '^native-obfuscator-function:' scoped.log)" -eq 1
    grep -q 'function=method_sum candidates=1 eligible=1 transformed=1 ' scoped.log
    grep -q 'skipped_prefix_functions=1 skipped_scope_functions=3' scoped.log
  done
  : > empty.txt
  "$llvm_bin/opt" -load-pass-plugin="$plugin" -verify-each \
    '-passes=native-encode<functions-file=empty.txt>' reports.ll -o disabled.bc 2> disabled.log
  grep -q 'native-obfuscator: native-encode candidates=0 eligible=0 transformed=0 .*skipped_scope_functions=5' disabled.log

  printf 'method_sum\nmethod_sum\n' > duplicate.txt
  printf 'method_sum\n\n' > blank.txt
  for selection in 'functions=method_sum+' 'functions=method_sum+method_sum' \
      'functions=method_sum;functions-file=llvm-functions.txt' 'functions-file=missing.txt' \
      'functions-file=../llvm-functions.txt' 'functions-file=duplicate.txt' 'functions-file=blank.txt'; do
    if "$llvm_bin/opt" -load-pass-plugin="$plugin" \
        "-passes=native-encode<$selection>" reports.ll -disable-output 2> invalid.log; then
      echo "Accepted invalid option $selection" >&2
      exit 1
    fi
    grep -q 'invalid native-encode options' invalid.log
  done
)
echo 'Per-function LLVM reports, exact selectors, empty scopes, and rejection reasons passed.'
