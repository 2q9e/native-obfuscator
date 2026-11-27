# Native Obfuscator

Native Obfuscator compiles selected Java and Kotlin methods to native code through JNI and bundles the native libraries in the output JAR. It supports JVM applications and Minecraft Fabric mods, with optional LLVM passes for arithmetic and control flow.

**To build, configure, and use the CLI, see the [usage guide](docs/usage.md).** It covers installation, method selection, Fabric, cross-compilation, LLVM profiles, and staged releases.

[Install from a release](docs/releases.md) · [Fabric example](examples/fabric/README.md) · [JNI coverage](docs/native-coverage.md) · [GitHub Actions](https://github.com/2q9e/native-obfuscator/actions/workflows/verify.yml) · [MIT license](LICENSE)

## Supported targets

The CLI packages native libraries for Linux x64, Linux ARM64, Windows x64, and Windows ARM64. CI runs differential tests on Java 21 and 25, builds the LLVM plugin against LLVM 22 and 23, and executes protected JARs on all four targets. See the [verification report](docs/verification.md) for test scope and results.

## Benchmarks

These measurements are from the same Linux ARM64 host. **Bold marks a lower observed value for Native Obfuscator in a direct comparison with a competitor.** Conversion coverage and binary sizes are exact for the tested artifacts. Timings and memory are medians for the fixture and run settings.

### Java-to-native comparison with Radioegor146

Both tools converted **7/7 requested methods** in the unchanged Java fixture, including the synchronized-block method. Both matched the original Java workload's checksums and reproduced their JARs byte-for-byte across five builds. Startup uses 11 fresh JVM samples. Steady-state timings use five JVM forks.

| Metric | Native Obfuscator | Radioegor146 3.5.4r |
| --- | ---: | ---: |
| Requested methods converted | 7/7 | 7/7 |
| Full build time | **280.3 ms** | 1,348.5 ms |
| Startup through first protected call | 64.7 ms | 58.5 ms |
| Per-call arithmetic | **3.31 ns/update** | 82.94 ns/update |
| Batch-32 arithmetic | **1.39 ns/update** | 36.04 ns/update |
| Peak build RSS | **65,760 KiB** | 122,128 KiB |
| Peak runtime RSS | 45,312 KiB | 44,448 KiB |
| Protected JAR size | **12,698 B** | 20,455 B |
| Uncompressed native payload | **67,952 B** | 68,848 B |

The original Java fixture measured 1.34 ns/update per call and 1.35 ns/update in batches. Both variants used Temurin 25.0.5. The comparison used Native Obfuscator `5fa68d2` and Radioegor146 `b3b3a40`. [Run samples and hashes](benchmarks/results/radioegor-full-coverage-linux-arm64-2026-11-27.json)

### LLVM pass comparison with Hikari

The baseline and both passes used the same Clang 22.1.8 toolchain and C workload. The Hikari case used its substitution pass. The Native Obfuscator case used `native-mix`.

| Metric | Clang 22 baseline | Native Obfuscator | Hikari |
| --- | ---: | ---: | ---: |
| Build time | 101.12 ms | **104.05 ms** | 123.07 ms |
| Startup | 1.097 ms | 1.134 ms | 0.942 ms |
| Per-call arithmetic | 3.007 ns/update | 5.071 ns/update | 3.388 ns/update |
| Batch-32 arithmetic | 3.016 ns/update | 2.971 ns/update | 2.972 ns/update |
| Peak runtime RSS | 1,040 KiB | **1,040 KiB** | 1,072 KiB |
| Executable size | 72,136 B | 72,136 B | 72,136 B |

The two passes were effectively tied on batched arithmetic in this run. [Run samples and hashes](benchmarks/results/c-competitors-linux-arm64-2026-11-27.json)

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
