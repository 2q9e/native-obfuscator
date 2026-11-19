# Build dependency inventory

| Component | Version | Use | License |
| --- | --- | --- | --- |
| [ASM core, tree, analysis, commons](https://asm.ow2.io/license.html) | 9.9 | Read, verify and rewrite classfiles | BSD-3-Clause |
| [Gson](https://github.com/google/gson/blob/gson-parent-2.13.2/LICENSE) | 2.13.2 | Fabric metadata and CLI reports | Apache-2.0 |
| [Error Prone Annotations](https://github.com/google/error-prone/blob/v2.41.0/COPYING) | 2.41.0 | Transitively used by Gson | Apache-2.0 |
| [JDK](https://openjdk.org/legal/gplv2+ce.html) | 21 or 25 | Java compiler, runtime, installed JNI header | OpenJDK GPL-2.0 with Classpath Exception |
| [Gradle](https://github.com/gradle/gradle/blob/v9.3.1/LICENSE) | 9.3.1 | Locally installed build tool | Apache-2.0 |
| [Zig](https://github.com/ziglang/zig/blob/0.15.2/LICENSE) | 0.15.2 tested | Optional toolchain for cross-compiling and linking | MIT plus bundled components |
| [LLVM and Clang](https://llvm.org/LICENSE.txt) | 23.1.4 tested | Optional build-time protection | Apache-2.0 with LLVM exception |

The Native Obfuscator source, launchers, and tests are distributed under the
project's MIT license. The installed CLI ships the ASM, Gson and Error Prone
Annotations libraries in separate JARs with their [license texts](../licenses).
Gradle, the JDK, and the native toolchains are installed separately.
