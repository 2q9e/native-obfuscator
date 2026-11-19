# LLVM arithmetic substitution

`NativeEncode` is an LLVM 23 new-pass-manager plugin. Use the same LLVM build for the
development headers, `clang`, `opt`, and `llc`. The plugin has been tested with LLVM
23.1.4 on Fedora Linux AArch64.

Build with a complete LLVM development installation:

```sh
cmake -S llvm -B build/llvm -DCMAKE_BUILD_TYPE=Release \
  -DLLVM_DIR="$(llvm-config --cmakedir)"
cmake --build build/llvm
ctest --test-dir build/llvm --output-on-failure
```

Fedora's LLVM CMake package expects the static component archives from
`llvm-static`. With an extracted LLVM SDK and matching system shared library,
this direct Linux build also works:

```sh
c++ -std=c++17 -O2 -Wall -Wextra -Werror -fPIC -shared \
  -isystem "$llvm_sdk/include" llvm/NativeEncode.cpp \
  /usr/lib64/libLLVM.so.23.1 -o NativeEncode.so
bash llvm/tests/verify.sh "$PWD/NativeEncode.so" "$llvm_sdk/bin"
bash llvm/tests/verify-cross.sh "$PWD/NativeEncode.so" "$llvm_sdk/bin"
```

Run the pass explicitly after ordinary optimization, then lower directly to
an object file:

```sh
clang -O2 -fPIC -emit-llvm -c generated.c -o baseline.bc
opt -load-pass-plugin="$plugin" -verify-each \
  -passes='native-encode<seed=42;max=8;prefix=method_>,verify' \
  baseline.bc -o encoded.bc
llc -O=2 -filetype=obj -relocation-model=pic encoded.bc -o encoded.o
```

`seed` is an unsigned 64-bit decimal number and defaults to zero. `max` limits
how many eligible instructions each function can transform and defaults to
eight. A value of zero disables the pass. `prefix` limits the pass to named
functions with that prefix. Generated JNI implementations use `method_`. Omit
`prefix` to allow every function. Options can appear in any order.

The pass substitutes scalar 8-, 16-, 32-, and 64-bit integer addition and
subtraction. For each candidate, it multiplies both operands by the same odd
factor, computes the operation, then multiplies by the factor's modular
inverse. In arithmetic modulo 2ⁿ, multiplication distributes over addition
and subtraction, so the resulting value is unchanged. Each input retains one
use. The pass skips instructions carrying no-wrap flags and functions marked
`optnone`. One substitution adds at most three IR instructions.

The native differential test runs 100,324 boundary and seeded input pairs
per build. It also checks that a fixed seed reproduces the bitcode, another
seed changes it, malformed options fail, and the prefix and per-function cap
are enforced. On Linux AArch64 the final linked test executable retains three
multiplication instructions in the selected 32-bit sum. The cross-target test
reports code size and disassembles an address-taken function in ELF and COFF
objects. Those objects are compiler and disassembly checks. Substitution
increases code size and work, and this identity can be simplified algebraically.
