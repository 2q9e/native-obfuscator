# LLVM native protection

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
bash llvm/tests/verify.sh "$PWD/NativeEncode.so" "$llvm_sdk/bin" cc native-diversify
bash llvm/tests/verify-cross.sh "$PWD/NativeEncode.so" "$llvm_sdk/bin"
bash llvm/tests/verify-cross.sh "$PWD/NativeEncode.so" "$llvm_sdk/bin" native-diversify
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

`native-encode` substitutes scalar 8-, 16-, 32-, and 64-bit integer addition,
subtraction, and multiplication. For addition and subtraction, it multiplies
both operands by the same odd factor, computes the operation, then multiplies
by the factor's modular inverse. For multiplication, it scales one operand,
computes the product, then applies the inverse. In arithmetic modulo 2ⁿ, these
steps preserve the original result because odd factors are invertible. Each
input retains one use. The pass skips instructions carrying no-wrap flags and
functions marked `optnone`. One substitution adds at most three IR instructions.
Each pass run prints candidate, eligible (including sites skipped by the cap),
and transformed site counts, guard and site-cap skips, function-level filters,
the site cap, and seed to stderr so a no-op run is visible in the build log.

`native-diversify` accepts the same options and defaults to two sites per
function. It replaces an eligible operation with two equivalent paths. One
performs the original operation, and the other uses modular encoding. A
seeded choice of four bits from a defined integer argument selects the path.
Both paths preserve the operation's behavior, including a poison result
arising from its other operand. The pass requires an explicit `noundef`
argument as one operand and skips functions with exceptional, convergent,
coroutine or indirect control flow. It also skips address-taken blocks.
An eligible site adds at most three blocks and ten IR instructions. The
branch can introduce input-dependent timing.

The native differential test runs 100,324 boundary and seeded input pairs
per build. It also checks that a fixed seed reproduces the bitcode, another
seed changes it, malformed options fail, and the prefix and per-function cap
are enforced. The diversification test also exercises an existing PHI with
two incoming edges, checks that functions without a safe selector retain their
body, and looks for the added branch after linking. The cross-target test
reports code size and disassembles an address-taken function in ELF and COFF
objects. Those objects are compiler and disassembly checks. Both passes
increase code size and work, and their arithmetic can be simplified
algebraically.
