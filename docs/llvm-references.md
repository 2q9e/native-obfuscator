# LLVM reference audit

This audit used the source and tests at the revisions below. The project implements
its LLVM pass with LLVM's public APIs and imports no source from these references.

## Hikari

The requested `PPKunOfficial/Hikari-LLVM19` URL resolves to `PPKunOfficial/Hikari-fix`.
The audited revision builds an out-of-tree shared plugin and requires LLVM 22
or later in CMake. It ships a GNU AGPL v3 license. The maintainer reports
testing on macOS AArch64 with C and Rust. Linux and Windows runtime support
was unverified in the inspected materials.
[Revision](https://github.com/PPKunOfficial/Hikari-fix/commit/edb82a24f15be27ec7573b12bef931e7d0ba7f4a) ·
[CMake](https://github.com/PPKunOfficial/Hikari-fix/blob/edb82a24f15be27ec7573b12bef931e7d0ba7f4a/CMakeLists.txt) ·
[License](https://github.com/PPKunOfficial/Hikari-fix/blob/edb82a24f15be27ec7573b12bef931e7d0ba7f4a/LICENSE) ·
[Platform notes](https://github.com/PPKunOfficial/Hikari-fix/blob/edb82a24f15be27ec7573b12bef931e7d0ba7f4a/README.md#hikari-llvm22)

The new pass manager entry invokes a legacy module scheduler that manually
runs module and function passes. It combines string protection, basic-block
splitting, bogus control flow, flattening, substitution, constant encoding
and indirect branches. The same scheduler also contains anti-hooking and
anti-debugging passes. Pipeline options mutate process-global flags, and the
default seed comes from the clock. Flattening lowers switch instructions before
checking for exception pads and unsupported terminators, so a skipped function
can still change. The project takes the explicit pipeline concept from this
reference and uses pass-instance options, fixed seeds and pre-mutation checks.
[Scheduler](https://github.com/PPKunOfficial/Hikari-fix/blob/edb82a24f15be27ec7573b12bef931e7d0ba7f4a/obfuscation/Obfuscation.cpp#L120-L245) ·
[Pipeline parser](https://github.com/PPKunOfficial/Hikari-fix/blob/edb82a24f15be27ec7573b12bef931e7d0ba7f4a/obfuscation/Obfuscation.cpp#L264-L309) ·
[Seed implementation](https://github.com/PPKunOfficial/Hikari-fix/blob/edb82a24f15be27ec7573b12bef931e7d0ba7f4a/obfuscation/CryptoUtils.cpp#L29-L45) ·
[Flattening](https://github.com/PPKunOfficial/Hikari-fix/blob/edb82a24f15be27ec7573b12bef931e7d0ba7f4a/obfuscation/Flattening.cpp#L31-L75)

The demo compiles C at `-O0` and displays sizes, strings and disassembly, with
some command failures tolerated. The virtualization acceptance script asserts
stdout equivalence and checks for generated structures. These are useful
examples of reporting, with evidence limited to their samples and compiler
settings. The audit found no measured performance cost for the requested
JNI workloads.
[C demo](https://github.com/PPKunOfficial/Hikari-fix/blob/edb82a24f15be27ec7573b12bef931e7d0ba7f4a/samples/run_demo.sh#L38-L96) ·
[Virtualization test](https://github.com/PPKunOfficial/Hikari-fix/blob/edb82a24f15be27ec7573b12bef931e7d0ba7f4a/samples/run_vmp.sh)

## Legacy ollvm

The inspected `und3ath/ollvm` revision carries Apache 2.0 with the LLVM
exception. The source still contains annotation analysis, a dependency-ordered
function pipeline, IR budgets, pass diagnostics and a test harness with seeded
inputs and an optional post-transform `-O2` correctness gate. Its final
inspected commit redirects development to xollvm and removes the LLVM build
and pass registry integration. Use the implementation and harness for comparison.
[Removal diff](https://github.com/und3ath/ollvm/commit/edd79f804a4cdd32dde27c24d2c42fb5d1e131c9) ·
[License](https://github.com/und3ath/ollvm/blob/edd79f804a4cdd32dde27c24d2c42fb5d1e131c9/LICENSE.TXT) ·
[Pipeline source](https://github.com/und3ath/ollvm/blob/edd79f804a4cdd32dde27c24d2c42fb5d1e131c9/llvm/lib/Transforms/Obfuscator/ObfuscationPipeline.cpp) ·
[Test runner](https://github.com/und3ath/ollvm/blob/edd79f804a4cdd32dde27c24d2c42fb5d1e131c9/llvm/utils/obfuscator/runner/pipeline.py) ·
[Test documentation](https://github.com/und3ath/ollvm/blob/edd79f804a4cdd32dde27c24d2c42fb5d1e131c9/TESTS.md)

## xollvm

`und3ath/xollvm` is the successor. The inspected revision uses
`add_llvm_pass_plugin` directly. Its release build specifies LLVM 22 and 23,
with dynamic plugins for Linux and macOS and a statically linked extension for
Windows. Linux and macOS plugins resolve LLVM symbols from the host, which
avoids loading a second LLVM command registry. Pin the toolchain used to build
and load a plugin independently of the OS and architecture targeted by the
generated object. The license is Apache 2.0 with the LLVM exception.
[Revision](https://github.com/und3ath/xollvm/commit/be963aa2f65ed090581cd1ad56bd9eeed9b98932) ·
[CMake](https://github.com/und3ath/xollvm/blob/be963aa2f65ed090581cd1ad56bd9eeed9b98932/CMakeLists.txt#L169-L195) ·
[Release matrix](https://github.com/und3ath/xollvm/blob/be963aa2f65ed090581cd1ad56bd9eeed9b98932/.github/workflows/release.yml#L27-L40) ·
[Build workflow](https://github.com/und3ath/xollvm/blob/be963aa2f65ed090581cd1ad56bd9eeed9b98932/.github/workflows/build.yml) ·
[License](https://github.com/und3ath/xollvm/blob/be963aa2f65ed090581cd1ad56bd9eeed9b98932/LICENSE)

The explicit `opt` entry can run on optimized bitcode. The automatic Clang hook
runs near the start of the optimizer, before inlining. The implementation
orders flattening before call indirection because the flattening pass normally
skips functions containing indirect calls. It uses per-function IR budgets,
optionally snapshots functions before applying a pass subject to a hard cap,
and verifies the module at the end. IR instruction counts help manage compiler
work but final section sizes are required to measure machine-code expansion.
[Pass entry](https://github.com/und3ath/xollvm/blob/be963aa2f65ed090581cd1ad56bd9eeed9b98932/registration/PluginEntry.cpp#L53-L113) ·
[Ordering](https://github.com/und3ath/xollvm/blob/be963aa2f65ed090581cd1ad56bd9eeed9b98932/ObfuscationPipeline.cpp#L45-L115) ·
[Budget and verification](https://github.com/und3ath/xollvm/blob/be963aa2f65ed090581cd1ad56bd9eeed9b98932/include/llvm/Transforms/Obfuscator.h#L336-L457)

Its release job performs pipeline and IR sanity checks. The standalone runtime
suite supplies a stronger comparison with seeded inputs, compiler optimization
gates and optional Linux AArch64 execution under QEMU. The inspected opaque
predicate and shield implementations depend on volatile loads or stores in
several paths. Their optimization behavior and performance cannot be inferred
from transformed IR size alone. The project uses a narrow, explicitly scheduled
pass and verifies actual machine code and semantics with its own fixtures.
[Release checks](https://github.com/und3ath/xollvm/blob/be963aa2f65ed090581cd1ad56bd9eeed9b98932/.github/workflows/build.yml#L206-L236) ·
[Test options](https://github.com/und3ath/xollvm/blob/be963aa2f65ed090581cd1ad56bd9eeed9b98932/utils/runner/cli.py#L109-L172) ·
[QEMU targets](https://github.com/und3ath/xollvm/blob/be963aa2f65ed090581cd1ad56bd9eeed9b98932/utils/runner/targets.py#L40-L70) ·
[Opaque predicates](https://github.com/und3ath/xollvm/blob/be963aa2f65ed090581cd1ad56bd9eeed9b98932/OpaqueUtils.cpp#L63-L125) ·
[Volatile barriers](https://github.com/und3ath/xollvm/blob/be963aa2f65ed090581cd1ad56bd9eeed9b98932/AntiOptimizationShield.cpp#L145-L195)
