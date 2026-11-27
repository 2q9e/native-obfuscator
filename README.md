# Native Obfuscator

Native Obfuscator compiles selected Java and Kotlin methods to native code through JNI and bundles the native libraries in the output JAR. It supports JVM applications and Minecraft Fabric mods, with optional LLVM passes for arithmetic and control flow.

**To build, configure, and use the CLI, see the [usage guide](docs/usage.md).** It covers installation, method selection, Fabric, cross-compilation, LLVM profiles, and staged releases.

[Install from a release](docs/releases.md) · [Fabric example](examples/fabric/README.md) · [JNI coverage](docs/native-coverage.md) · [GitHub Actions](https://github.com/2q9e/native-obfuscator/actions/workflows/verify.yml) · [MIT license](LICENSE)

## Supported targets

The CLI packages native libraries for Linux x64, Linux ARM64, Windows x64, and Windows ARM64. CI runs differential tests on Java 21 and 25, builds the LLVM plugin against LLVM 22 and 23, and executes protected JARs on all four targets. See the [verification report](docs/verification.md) for test scope and results.

## Benchmarks

These measurements are from the same Linux ARM64 host. **Bold marks a clear measured advantage for Native Obfuscator over the competitor.** Conversion coverage and binary sizes are exact for the tested artifacts. Timings and memory are medians for the fixture and run settings.

### Java-to-native comparison with Radioegor146

Both project revisions and Radioegor146 converted all seven requested methods, matched the original Java checksums, and reproduced their JARs byte for byte across five builds. Startup uses 31 fresh JVMs per case. Steady-state timings use seven JVM forks.

| Metric | Native Obfuscator before | Native Obfuscator after | Radioegor146 3.5.4r |
| --- | ---: | ---: | ---: |
| Requested methods converted | 7/7 | 7/7 | 7/7 |
| Full build time | 241.4 ms | **241.1 ms** | 1,091.5 ms |
| Startup through first protected call | 47.88 ms | 42.70 ms | 36.86 ms |
| Per-call arithmetic | 3.31 ns/update | **3.31 ns/update** | 82.43 ns/update |
| Batch-32 arithmetic | 1.39 ns/update | **1.39 ns/update** | 35.96 ns/update |
| Peak build RSS | 66,928 KiB | **66,416 KiB** | 121,760 KiB |
| Peak runtime RSS | 51,120 KiB | 45,936 KiB | 45,520 KiB |
| Protected JAR size | 12,698 B | **12,739 B** | 20,455 B |
| Uncompressed native payload | 67,952 B | **67,952 B** | 68,848 B |

The new loader cut process startup time by 10.8% and peak runtime RSS by 10.1%. Radioegor146 still started 5.84 ms sooner. The protected JAR grew by 41 B. The original Java fixture measured 1.34 ns/update per call and 1.35 ns/update in batches. [Run samples and hashes](benchmarks/results/performance-followup-linux-arm64-2026-11-27.json)

### LLVM pass comparison with Hikari

All four cases used the same Clang 22.1.8 toolchain and C workload. The Hikari case used its substitution pass. Both project revisions used `native-mix` with identical settings.

| Metric | Clang 22 baseline | Native Obfuscator before | Native Obfuscator after | Hikari |
| --- | ---: | ---: | ---: | ---: |
| Build time | 74.01 ms | 80.96 ms | 80.42 ms | 77.56 ms |
| Startup | 0.450 ms | 0.445 ms | 0.445 ms | 0.450 ms |
| Single-step arithmetic | 1.778 ns/update | 2.499 ns/update | 2.473 ns/update | 1.295 ns/update |
| Per-call arithmetic | 2.950 ns/update | 5.069 ns/update | 4.687 ns/update | 3.396 ns/update |
| Batch-32 arithmetic | 2.973 ns/update | 2.972 ns/update | 2.972 ns/update | 2.973 ns/update |
| Peak build RSS | 84,848 KiB | 84,864 KiB | 84,864 KiB | 84,864 KiB |
| Peak runtime RSS | 1,040 KiB | 1,040 KiB | 1,040 KiB | 1,072 KiB |
| Executable size | 72,136 B | 72,136 B | 72,136 B | 72,136 B |

Both project revisions rewrote the same five sites across two C kernels. The linked per-call kernel shrank from 23 to 19 instructions, and its median time per update fell 7.5%. Hikari remained faster on the single-step and per-call workloads. [Run samples and hashes](benchmarks/results/performance-followup-linux-arm64-2026-11-27.json)

### O-MVLL against its Android NDK baseline

Both builds used Android NDK r29. QEMU ran the x64 compiler and plugin. The resulting static Android ARM64 executables ran directly on the Linux ARM64 host. The measurements apply to this NDK pair.

| Metric | NDK baseline | O-MVLL 1.11.1 |
| --- | ---: | ---: |
| Build time | 1,911 ms | 2,294 ms |
| Startup | 0.913 ms | 1.012 ms |
| Per-call arithmetic | 1.468 ns/update | 1.443 ns/update |
| Batch-32 arithmetic | 1.443 ns/update | 1.989 ns/update |
| Peak runtime RSS | 528 KiB | 528 KiB |
| Executable size | 2,140,392 B | 2,140,456 B |

All C configurations matched workload checksums and reproduced their own executables across three builds. The O-MVLL per-call kernel had identical linked instruction bytes in both builds. Its timing difference is within the scope of the observed samples. [Run samples and hashes](benchmarks/results/c-competitors-linux-arm64-2026-11-27.json)

See the [comparative evaluation](docs/competitive-evaluation.md) for setup, limitations, and pending competitors, or the [benchmark runner](benchmarks/README.md) to reproduce the measurements. The benchmarks measure conversion, correctness, performance, size, and linked instruction changes. Independent reconstruction difficulty remains unmeasured.
