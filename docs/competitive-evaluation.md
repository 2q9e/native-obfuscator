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

A later run of the unchanged fixture converted **7/7** methods with Native
Obfuscator after structured synchronized-block support was added. The table
above records the original comparison.

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

### O-MVLL on Android ARM64

O-MVLL 1.11.1 and the matched NDK r29 baseline both passed all fixture
checksums and reproduced their own executables across three builds.
The official x86-64 compiler and plugin ran under QEMU 11.1.3 with an Ubuntu
24.04 x86-64 userspace. Both produced static Android API 24 ARM64 executables,
which ran directly on the same Fedora Asahi Linux ARM64 kernel. Build timing
and build RSS include emulation. Runtime measurements describe the Linux
host and static Android binaries. An Android device was outside this run.

| Case | Build ms | Build peak RSS KiB | Startup ms | Runtime peak RSS KiB | Executable bytes | Step ns/update | Per-call ns/update | Batch-32 ns/update |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| NDK r29 baseline | 1,905.94 | 208,880 | 1.002 | 528 | 2,140,392 | 1.459 | 2.428 | 1.561 |
| O-MVLL arithmetic, two rounds | 2,249.98 | 268,992 | 1.145 | 528 | 2,140,456 | 3.054 | 1.449 | 2.062 |

The plugin logged arithmetic changes. The linked step and batch kernels
grew from 16 to 36 and from 56 to 100 instruction bytes respectively.
The per-call kernel retained the same 40-byte encoding, so its timing
difference does not establish a speedup from the pass. Compare this result
within the NDK pair because the earlier C runs used other compilers and
a different libc and link mode. JVM conversion coverage is inapplicable.
[Samples and artifact hashes](../benchmarks/results/omvll-android-arm64-2026-11-27.json)

Use the [official O-MVLL Linux release](https://github.com/open-obfuscator/o-mvll/releases/tag/1.11.1)
with SHA-256 `841e6fb4a1b114da0f72db66b269657c66adf73838e091534cec65e359812886`
and [Android NDK r29](https://github.com/android/ndk/releases/tag/r29).
Create two C-lane adapter entries with the following argument prefixes.
Append `{input}` and `{output}` to each build command and use the same
prefix with `--version` as the version command.

```text
python3 /repo/benchmarks/omvll-adapter.py --clang /ndk/bin/clang
python3 /repo/benchmarks/omvll-adapter.py --clang /ndk/bin/clang --plugin /release/omvll-ndk.so --python-lib /release/Python-3.10.7/Lib
```

For an ARM64 build host, add `--qemu /path/to/qemu-x86_64-static --qemu-root /path/to/x86_64-rootfs`
to both prefixes. Set the O-MVLL entry's `relativeTo` to the NDK baseline.
Run the comparison command below from a host that can execute the resulting
static ARM64 binaries directly. The recorded settings use the same seeds
and sample counts as the earlier comparisons.

### Radioegor146 and Hikari

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
  compiler came from those attempts. A follow-up isolated build with
  `-include cstdint` and a local ARM64 endianness declaration progressed
  to the SLP vectorizer. GCC 16 then rejected its non-const scheduling
  comparator. These portability experiments were excluded from the results.
- Obfuscator-LLVM was paused at the user's direction after the compatibility
  build progressed into Clang's C++ frontend. There is no completed benchmark.
- [Tigress 4.2 supports Linux ARM64](https://tigress.wtf/tigress-download.html).
  No authorized local installation was available.

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
