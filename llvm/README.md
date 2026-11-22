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

`native-mix` accepts the same options and defaults to four sites per function.
It substitutes scalar 8-, 16-, 32-, and 64-bit `add`, `sub`, `mul`, `and`,
`or`, and `xor` instructions. The seed and function name select the identity
or the multiplication form and its offset or bit mask. Arithmetic wraps at
the operand width.

| Operation | Example identity |
| --- | --- |
| `add` | `(x ^ y) + 2 * (x & y)` |
| `sub` | `(x ^ y) - 2 * (~x & y)` |
| `mul` | `(x + k) * y - k * y` or `(x ^ highBit) * (y & m) + x * (y & ~m)` with an even mask `m` |
| `and` | `(x + y) - (x \| y)` |
| `or` | `(x + y) - (x & y)` |
| `xor` | `(x \| y) - (x & y)` |

The pass requires LLVM ValueTracking to prove that both operands are free of
poison and `undef`, since the replacement can use each operand more than once.
Examples of eligible inputs include integer constants, `noundef` arguments,
and results of `freeze`. The pass skips `optnone` and `naked` functions and
instructions with poison-generating flags such as `nuw`, `nsw`, and `disjoint`.
One substitution adds at most five IR instructions. The even mask makes the
high-bit toggle contribute a multiple of the wrap modulus. In the LLVM 23
multiply fixture, an additional InstCombine and Reassociate pipeline reduced
the offset form to a single multiply and left two multiplies in the masked
form. Machine code can still simplify the high-bit toggle.

`native-route` accepts the same options and defaults to one site per function.
It routes a scalar 8-, 16-, 32-, or 64-bit `add`, `sub`, or `mul` through two
equivalent paths and joins them with a PHI. A seeded bit of an operand that
LLVM can prove both defined and non-poison selects the path. For addition and
subtraction, each path has a distinct invertible scale. For multiplication,
each path uses a distinct offset and repeats only the defined selector operand.
The other operand is used once on each path and can still propagate poison.
The pass skips unsupported flags, constant-only selectors, address-taken
blocks, and functions with exceptional, convergent, coroutine, indirect,
garbage collection, or `returns_twice` control flow. Each site adds at most
three blocks and thirteen IR instructions. Lowering can convert the branch
to conditional selection. The selected path can affect timing.

The extended passes report rejected flags and types, with `skipped_undefined`
for mix and `skipped_unsafe_blocks` and `skipped_guard` for route. Each selected
function gets a report, including functions with zero candidates. Configure
both passes explicitly. When composing passes, each sees the previous pass's
output and applies its own cap to eligible instructions in that IR.

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

The extended differential test runs 165,825 pairs across the six operations
at all four widths, a dependent chain, and a function with an existing PHI.
It includes every 8-bit input pair, wider boundaries, and deterministic
pseudorandom inputs. It checks rejection reasons, caps, option parsing, seed
reproducibility, IR verification, and representative linked instructions.
Linux CTest runs this test when a Python 3 interpreter is available. To run
it directly with a loadable plugin:

```sh
python3 llvm/tests/verify-extended.py --llvm-bin "$llvm_sdk/bin" \
  --plugin "$PWD/NativeEncode.so" --cc cc
```

With the Windows LLVM 23.1.6 static `opt` wrapper and Zig 0.15.2, the baseline,
four mix seeds, and two route seeds each passed the differential test. In the
linked x86-64 PE at seed 42, mix `method_add32` used `andl` and `xorl`, route
`method_add32` used a conditional jump and two modular sequences, and route
`method_mul32` used a conditional jump and two offset products. LLVM lowered
route selection to `csel` in the AArch64 ELF and COFF object probes. For the
address-taken `method_1` fixture, `.text` grew from 4 to 9 bytes with mix and
from 4 to 50 with route on x86-64, and from 8 to 16 with mix and from 8 to 60
with route on AArch64. These measurements use seed 42 and one site per
function. The linked evidence is specific to the tested toolchain and inputs.

```powershell
py -3 llvm/tests/verify-extended.py --llvm-bin $llvmBin --static-opt $staticOpt `
  --cc $zig --cc-arg=cc --cc-arg=-target --cc-arg=x86_64-windows-gnu `
  --target x86_64-windows-gnu
```
