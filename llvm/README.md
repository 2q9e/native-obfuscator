# LLVM native protection

`NativeEncode` supports the LLVM 22 and 23 new pass managers. Build the plugin
against the LLVM installation that provides `clang`, `opt`, and `llc` for the
pipeline. The plugin tests ran on Fedora Linux AArch64 with LLVM 22.1.8 and 23.1.4.
The pass registry lives in `NativeEncode.cpp`. Integer and control-flow rewrites
each have a separate translation unit.

Build with a complete LLVM development installation:

```sh
cmake -S llvm -B build/llvm -DCMAKE_BUILD_TYPE=Release \
  -DLLVM_DIR="$(llvm-config --cmakedir)"
cmake --build build/llvm
ctest --test-dir build/llvm --output-on-failure
```

Fedora's LLVM CMake package expects the static component archives in the
matching development installation. With an extracted LLVM SDK and matching
shared library, this direct Linux build also works:

```sh
c++ -std=c++17 -O2 -Wall -Wextra -Werror -fPIC -shared \
  -isystem "$llvm_sdk/include" llvm/NativeEncode.cpp llvm/IntegerTransforms.cpp \
  llvm/ControlFlowTransforms.cpp "$llvm_library" -o NativeEncode.so
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
`prefix` to allow every function. Use `functions=NAME+NAME` to select exact
symbols or `functions-file=BASENAME` to read exact symbols, one per line, from
a file in `opt`'s working directory. An empty file selects zero functions.
The exact selection and prefix must both match when supplied together. Options
can appear in any order.

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

`native-compare` accepts the same options and defaults to one site per function.
It splits an ordered branch comparison between two nonconstant scalar integers
of width 8, 16, 32, or 64. The seed and function name determine the split bit.
If the upper portions differ, their ordering decides the branch. If they are
equal, an unsigned comparison of the lower portions decides it. The upper
comparison preserves the original signedness. Each site adds two blocks and
one conditional branch in the IR, and lowering can use conditional selection.
The compiler freezes operands it cannot prove defined before using them
more than once. Eligibility requires the comparison to feed only the branch.
The pass skips `samesign` comparisons and uses the same control-flow safety
checks as route. Use the explicit pipeline `native-compare<seed=42;max=1;prefix=method_>`.

Integer and comparison passes report rejected flags and types.
Mix also reports `skipped_undefined`. Control-flow passes report unsafe
blocks or functions, and passes requiring a selector report guard rejections.
Dispatch reports candidate branches rejected by its graph limits as
`skipped_size` and those rejected by spill types as `skipped_type`.
Each selected function gets a report, including functions with zero candidates.
When composing passes, each sees the previous pass's output and applies its
own cap to eligible instructions in that IR.

`native-dispatch` uses a shared switch dispatcher for a function's eligible
control flow. It lowers PHIs and values shared across blocks to stack slots so
each branch can store a seeded destination label and return to the dispatcher.
The private state slot uses volatile reads and writes so the dispatcher survives
normal optimization. A function must contain a conditional branch and have at
most 32 blocks and 384 instructions when the pass starts. The default `max=16`
is the budget for original branch sites per function. If the function exceeds
the cap or uses unsupported control flow or value types such as x86 AMX tiles,
the pass keeps its body unchanged.
The `maximum` CLI preset runs this pass after its other LLVM passes. For an
explicit pipeline use `native-dispatch<seed=42;max=16;prefix=method_>`.

The differential fixture compares dispatch results over 727,040 integer
relations and 128 loop lengths, including a loop with critical edges and PHIs.
It also checks seed reproducibility, budget rejection, dispatcher survival at
`-O2`, and object generation for all four targets. The dispatcher can increase
code size and slow loops. Measuring reconstruction difficulty requires a
separate decompiler or analyst benchmark.

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
The comparison fixture checks all eight ordered predicates across the four
widths, an exhaustive set of 8-bit operand pairs, wider boundaries, and 128 loop
lengths with PHIs on both successor edges. It checks the linked host executable
and cross-compiled ELF and COFF objects for both architectures. In the LLVM
23 comparison probe, InstCombine and SimplifyCFG converted the split branch
into a select but retained both comparisons and the upper-bit equality test.
Linux CTest runs this test when a Python 3 interpreter is available. To run
it directly with a loadable plugin:

```sh
python3 llvm/tests/verify-extended.py --llvm-bin "$llvm_sdk/bin" \
  --plugin "$PWD/NativeEncode.so" --cc cc
python3 llvm/tests/verify-randomized.py --llvm-bin "$llvm_sdk/bin" \
  --plugin "$PWD/NativeEncode.so" --cc cc
```

The randomized suite generates 32 functions per program seed across four
integer widths. It links and executes each individual pass and two combined
pipelines against the same boundary and random inputs. It also checks
bitcode reproducibility and dispatcher handling of AMX values and the full
32-bit range of state labels.

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
