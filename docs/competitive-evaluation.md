# Comparative evaluation

## Measured run

The Linux ARM64 run used JDK 25.0.5, GCC 16.2.1 for the JVM lane, and Clang/LLVM
22.1.8 for the matched C lane. Each case used three builds, five startup samples,
three steady-state forks, five warmups, and nine trials of 524,288 updates per
fork. The semantic probes used seeds 637 and 2309737967. Build and startup
times are wall-clock medians. RSS is the median steady-state command peak.
The [recorded samples and hashes](../benchmarks/results/linux-arm64-2026-11-27.json)
include the fixture settings, sampled timings, and artifact digests.

| JVM case | Build ms | Startup ms | Peak RSS KiB | JAR bytes | Native requests served | Per-call ns/update | Batch-32 ns/update |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Original JVM | — | 123.59 | 40,784 | 3,592 | 0/7 | 1.34 | 1.35 |
| Native Obfuscator, compatibility | 310.11 | 297.87 | 49,952 | 11,435 | 6/7 | 3.31 | 1.39 |
| Radioegor146, 3.5.4r | 2,502.44 | 202.23 | 44,352 | 20,455 | 7/7 | 92.57 | 36.03 |

Both JNI variants passed the same fixture probes and returned identical
checksums for timed workloads. All three builds per configuration succeeded
and yielded identical bytes. Radioegor converted the synchronized-block
fixture and had lower median startup time and RSS than this project in this run.
The built-in JNI run followed the existing unsupported policy for that method.
The JVM lane measures each tool's complete JAR build with the same Java input.
Its precise settings and outputs describe this fixture and host.

| Matched C case | Build ms | Startup ms | Peak RSS KiB | Executable bytes | Per-call ns/update | Batch-32 ns/update | Linked kernel encodings changed |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Clang 22 baseline | 293.55 | 0.86 | 1,040 | 72,136 | 2.95 | 2.97 | Baseline |
| Hikari LLVM 22 port, substitution | 194.32 | 0.92 | 1,040 | 72,136 | 3.31 | 2.97 | 2/3 |
| Native Obfuscator, `native-mix` | 213.75 | 1.02 | 1,072 | 72,136 | 5.07 | 2.97 | 2/3 |

The C cases passed all checksum comparisons and each reproduced its own
executable across three builds. Hikari's substitution case had lower per-call
runtime than this project's mixing case in this run. The build intervals also
include compiler process startup and filesystem effects. JNI overhead and JVM
JIT behavior make cross-lane runtime comparisons unsuitable.

## Reproduce the measured configurations

Use the [comparison runner](../benchmarks/README.md) and an adapter manifest.
For the Java lane, use [Radioegor146 revision b3b3a40](https://github.com/Radioegor146/native-obfuscator/commit/b3b3a40ac27c78e343631b078fa62f932edd09ff).
Build its `obfuscator` and `annotations` Java sources with the dependencies
declared in that revision. The measured run compiled those sources directly
with `javac` and Maven Central dependencies after the Gradle plugin download
failed. Put compiled classes, `obfuscator/src/main/resources`, and dependency
JARs on the classpath used by `radioegor-adapter.py`. Use `{input}`,
`{output}`, and `{selection}` as the final build arguments. The adapter
includes the synthesized class initializer in the whitelist so registration
receives valid JVM code, then uses CMake/Ninja and repackages the host library.

The C lane used the [Hikari LLVM 22 port at edb82a2](https://github.com/ioctl-codex/Hikari/commit/edb82a24f15be27ec7573b12bef931e7d0ba7f4a).
The measured out-of-tree build compiled the unmodified `obfuscation/*.cpp`
sources as a shared C++17 library, with the source directory and LLVM 22 headers
on the include path, `GIT_COMMIT_HASH` set to that revision, and linked against
`libLLVM.so.22.1`. The measured plugin's SHA-256 is recorded in the adapter's
`--version` output.

Register three `llvm-adapter.py` configurations with the same LLVM 22 Clang.
Pass `--clang-arg=-DBENCHMARK_KERNEL=__attribute__((annotate("sub")))`
as a single argument to all three. The baseline has no pass plugin. Hikari
uses `--passes hikari --opt-arg=-aesSeed=42` with the Hikari plugin. This
project uses `--passes 'native-mix<seed=42;max=8;prefix=workload_>'` with
its plugin. Set both transformed cases' `relativeTo` to the matched Clang baseline.

```sh
python3 benchmarks/compare.py --adapters adapters.json --output build/competitive-results \
  --llvm-objdump /path/to/llvm-22/bin/llvm-objdump \
  --build-runs 3 --startup-runs 5 --forks 3 --warmups 5 --trials 9 --iterations 524288
```

Unmeasured configurations have specific prerequisites:

- The original [Obfuscator-LLVM 4.0 branch](https://github.com/obfuscator-llvm/obfuscator/tree/llvm-4.0)
  at `42f732c` requires older build tooling. CMake 4.3 rejected its CMP0051
  setting. CMake 3.31.10 configured it, but GCC 16 rejected the unmodified
  source's undeclared `intptr_t` in `llvm/ADT/STLExtras.h`. No runnable
  compiler came from those attempts.
- The inspected [O-MVLL v1.11.1 package](https://github.com/open-obfuscator/o-mvll/releases/tag/v1.11.1)
  supplies an x86-64 Linux-host plugin for Android NDK r29 tooling. It
  could not load into the ARM64 host compiler used here. A comparison needs
  a matched Android toolchain, same-target baselines, and a runtime device.
- No authorized local Tigress installation was available. A run needs a
  licensed installation that supports the selected host and target.

## Independently check the claims

Pass diagnostics establish that IR was changed. Optional linked-kernel hashes
establish whether instruction encodings differ in the output. Differential
execution checks that each artifact matched the tested inputs. Runtime, size
and memory describe costs. These are distinct measurements.

For a protection-effectiveness study, preregister what participants should
recover and how partial results will be scored. Use blinded, target-matched
artifacts with a held-out ground truth. Record tool versions, time budgets,
correct recovery and false-positive rates, and publish the raw observations.
The existing [Ghidra helper recovery exercise](analysis-resistance.md#headless-decompiler-recovery)
can check a narrower call-recovery task. No independent recovery scores were
measured for the comparison above.
