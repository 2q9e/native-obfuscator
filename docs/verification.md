# Local verification

These checks ran on Fedora Asahi ARM64 with Temurin 25.0.5, Zulu 21.0.13.1,
Zig 0.15.2, and Fedora LLVM 23.1.4. Java classfile and JNI verification
were enabled in the differential runs.

| Check | Result |
| --- | --- |
| Gradle application distribution | Built with Gradle 9.3.1 |
| Java 21 and 25 differential fixture | Both versions matched digest `55cfd63f18498e67`. Eighteen methods became native. Lambda, array access, and explicit exception throw kept the Java body. |
| Modern classfiles | Java 21 and 25 executed a retained `ConstantDynamic` bootstrap once, converted a method with supplementary Unicode in its name and descriptor, and preserved record metadata and generic bridge dispatch. |
| Tamper and archive checks | Native library checksum mismatch, duplicate archive entries, traversal paths, signing files, and malformed classfiles were rejected. Strict-mode failure preserved an existing output file. |
| LLVM arithmetic pass | Host differential test passed 100,324 input pairs per build and checked seed reproducibility, input validation, prefix and budget filtering, and instruction survival. |
| LLVM control-flow diversification | Native differential passed with 100,324 pairs per build. Final linked Linux ARM64 code retained the conditional branch. Linux and Windows ELF/COFF inspection showed the branch on both architectures. |
| LLVM all-target packaging and host execution | A single JAR packaged all four libraries. The Linux ARM64 library ran with JNI checks enabled and matched the JVM digest. |
| LLVM Linux x64 and both Windows targets | Cross-compiled. Object disassembly tests confirm arithmetic transformation survives code generation on all four architectures and formats. |

Example LLVM balanced build with seed 42, binary bytes including headers and dependencies:

| Target | Matching baseline | Protected |
| --- | ---: | ---: |
| Linux x64 | 9,280 | 9,648 |
| Linux ARM64 | 9,400 | 9,896 |
| Windows x64 | 170,496 | 171,008 |
| Windows ARM64 | 158,720 | 159,232 |

One Fedora ARM64 run of `benchmarks/run.sh` used nine measured samples after
five warmups. Values are median nanoseconds per call. The original Java JIT
can inline the boundary workload, which accounts for much of the difference.

| Workload | Java | JNI only | Balanced LLVM |
| --- | ---: | ---: | ---: |
| Boundary | 0.943 | 3.018 | 3.019 |
| Arithmetic | 38.520 | 37.617 | 41.894 |

The three runs matched both workload checksums. The balanced arithmetic
workload took 8.76% longer than Java in this run. These timings are from a
single host and measure a small synthetic workload.

See the [LLVM pass test instructions](../llvm/README.md) for disassembly and
reproducibility checks. The workflow runs target-native checks separately from
cross-compilation.
