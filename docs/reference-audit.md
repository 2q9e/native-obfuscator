# Reference audit

The design review compared jnic-x's pipeline with six other reference projects. The translation, JNI registration, packaging, and LLVM passes were implemented in this repository. The reference checkouts are kept under the ignored `.references/` directory, with a sparse checkout for the large LLVM fork. The project distribution contains no implementation or generated code from those checkouts.

| Reference | Pinned revision | Root license |
| --- | --- | --- |
| [jnic-x](https://github.com/JoyelTheDev/jnic-x/tree/9c317752e35a7a278f43cd820966bf0fb4045325) | `9c31775` | MIT, with separately licensed files |
| [native-obfuscator](https://github.com/radioegor146/native-obfuscator/tree/b3b3a40ac27c78e343631b078fa62f932edd09ff) | `b3b3a40` | GPL-3.0 with a scoped output exception |
| [Open-MyJ2c](https://github.com/MyJ2c/Open-MyJ2c/tree/814dd48ea6cc0b7e6b5663d8cc8f1b50684739fe) | `814dd48` | GPL-3.0 |
| [masxinlingvonta](https://github.com/superblaubeere27/masxinlingvonta/tree/26b30ff571fb6f633f41624ad56b66d430b11c6a) | `26b30ff` | GPL-3.0 |
| [j2c](https://github.com/nuym/j2c/tree/1d125a332420508d1400016b79b84379ed33cb83) | `1d125a3` | GPL-3.0 |
| [Hikari-LLVM19, now Hikari-fix](https://github.com/PPKunOfficial/Hikari-fix/tree/edb82a24f15be27ec7573b12bef931e7d0ba7f4a) | `edb82a2` | AGPL-3.0 |
| [ollvm](https://github.com/und3ath/ollvm/tree/edd79f804a4cdd32dde27c24d2c42fb5d1e131c9) | `edd79f8` | Apache-2.0 with LLVM exception at root |

## jnic-x adoption decisions

- Retain descriptor-aware selection, generated C with JNI registration and Zig target compilation as architectural concepts. jnic-x's implementation derives C symbols without the descriptor and uses inline assembly specific to x86, direct signed operations, and cast behavior that differs from the JVM. The replacement uses sequential symbols, verified stack frames, unsigned wrapping operations, explicit edge cases, and target-independent C. [Method emitter](https://github.com/JoyelTheDev/jnic-x/blob/9c317752e35a7a278f43cd820966bf0fb4045325/src/main/java/cn/sky/jnic/generator/CMethodEmitter.java) · [Instruction emitter](https://github.com/JoyelTheDev/jnic-x/blob/9c317752e35a7a278f43cd820966bf0fb4045325/src/main/java/cn/sky/jnic/generator/CInstructionEmitter.java)
- Replace the registration and loader implementations. The packaging code references a different package than the loader declares, appends registration after existing initialization, and patches constants that Java inlines. The new implementation registers the declaring class before its original initializer and validates binary resources before loading. [Processor](https://github.com/JoyelTheDev/jnic-x/blob/9c317752e35a7a278f43cd820966bf0fb4045325/src/main/java/cn/sky/jnic/process/NativeProcessor.java) · [Loader](https://github.com/JoyelTheDev/jnic-x/blob/9c317752e35a7a278f43cd820966bf0fb4045325/src/main/java/cn/sky/jnic/loader/JNICLoader.java)
- Reject the binary output on any target failure. jnic-x's Gradle project targets Java 25 and uses ASM 9.9, but source inspection found no test sources and no verified differential or runtime matrix. Its Java-to-C toolchain lacks ARM64 target mappings, and compiler failures can leave output with missing native targets. [Build](https://github.com/JoyelTheDev/jnic-x/blob/9c317752e35a7a278f43cd820966bf0fb4045325/build.gradle) · [Compiler](https://github.com/JoyelTheDev/jnic-x/blob/9c317752e35a7a278f43cd820966bf0fb4045325/src/main/java/cn/sky/jnic/process/ZigCompiler.java)
- Exclude jnic-x's ASM wrappers and bundled JNI header. The wrapper sources match the separately GPL-licensed Radon utilities, and the bundled JNI header has a distinct Oracle copyright and GPL-2.0 with Classpath exception notice. Use the build JDK's installed header. [jnic-x wrappers](https://github.com/JoyelTheDev/jnic-x/tree/9c317752e35a7a278f43cd820966bf0fb4045325/src/main/java/cn/sky/jnic/utils/asm) · [Original Radon wrappers](https://github.com/ItzSomebody/radon/tree/7d3e8719afdb832c95ca19e2817e33d2b3f36c32/xyz.itzsomebody.radon/src/main/java/xyz/itzsomebody/radon/utils/asm) · [JNI header](https://github.com/JoyelTheDev/jnic-x/blob/9c317752e35a7a278f43cd820966bf0fb4045325/src/main/resources/jni.h)

The [JVM comparison](jvm-references.md) documents the other translators. Source observation here describes reference behavior without claiming runtime verification.
