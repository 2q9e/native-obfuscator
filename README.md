# Native Obfuscator

Native Obfuscator converts explicitly selected JVM methods in an existing JAR to JNI functions. It accepts the final JAR produced by a Java obfuscator or Fabric Loom and bundles the native libraries. The input JAR is analyzed as data and its classes are never loaded by the CLI.

## Build and use

Use JDK 21 or 25, an installed Gradle 9.3.1, and a Linux C11 compiler for a host build. Use Zig 0.15.2 for the four bundled targets.

The build scripts use Gradle from `GRADLE_HOME` or `PATH`. The installed CLI uses
Java from `JAVA_HOME` or `PATH` and accepts JVM options through `JDK_JAVA_OPTIONS`.

```sh
./gradlew installDist
tool=build/install/native-obfuscator/bin/native-obfuscator
"$tool" analyze --input input.jar --include 'my/mod/Math#mix(II)I'
"$tool" protect --input input.jar --output protected.jar \
  --include 'my/mod/Math#mix(II)I' --target all --zig /path/to/zig
```

Selection uses `internal/Class#name(descriptor)`. `*` matches zero or more characters and `?` matches one. Repeat `--include` and `--exclude` as needed. The CLI emits one JSON record for each selected method. By default, an unsupported selected method fails the build. Use `--unsupported retain` to keep its body in Java. Signed input JARs must be protected before signing. `--emit-c` writes the generated C for inspection.

Use `--enable-native-access=ALL-UNNAMED` for classpath launches on Java 25. Named modules need native access enabled for the module that contains the generated loader. Libraries are extracted into a private per-classloader temporary directory on systems with POSIX permissions. The checksum detects corruption of the library resource; use publisher signing for JAR authenticity.

## Coverage

The translator handles primitive numeric instructions, branches, loops, table and lookup switches, primitive returns, static and instance methods, and reference identity checks. It preserves existing classfile versions, and registers native code before the original declaring-class initializer. Constructors, interface methods, reference returns, JVM linkage operations such as calls and field access, exception handlers, array operations, and unrecognized instructions remain outside the current translation subset. The CLI excludes classes named by Fabric entrypoint, mixin, language-adapter, and access-widener metadata, as well as multi-release classes.

The target list includes Linux x64 and ARM64 and Windows x64 and ARM64. A single artifact covers each respective OS and architecture family. Cross-compilation and native execution are tested separately.

```sh
JAVA_RELEASE=21 bash src/test/integration.sh "$tool"
JAVA_RELEASE=25 bash src/test/integration.sh "$tool"
```

Use the matching JDK in `JAVA_HOME` for each test. Run
`bash src/test/generated/verify.sh "$tool"` for generated stack and arithmetic
programs and `bash src/test/modern/verify.sh "$tool"` for modern classfiles. The
Kotlin verification script uses the pinned Kotlin compiler bundled with
Gradle 9.3.1. Set `GRADLE_HOME` to your Gradle installation and run
`bash src/test/kotlin/verify.sh "$tool"`.

See the [dependency inventory](docs/dependencies.md) for the licenses shipped with the CLI.

## LLVM protection

Build the project-owned [LLVM 23 pass plugin](llvm/README.md) with the same LLVM
version as `clang` and `opt`. Normal optimization runs before the selected
protection passes. The final code generation step verifies the IR and lowers it
without running another optimization pipeline.

```sh
"$tool" protect --input input.jar --output protected.jar \
  --include 'my/mod/Math#mix(II)I' \
  --clang /path/to/clang --llvm-opt /path/to/opt --llvm-plugin /path/to/NativeEncode.so \
  --profile balanced --seed 42 --max-growth 25
```

Compatibility is the default without LLVM. Balanced, aggressive and maximum
allocate up to 4, 12 and 32 arithmetic substitutions per protected function.
Aggressive and maximum also attempt up to 1 and 3 equivalent conditional
paths per function when the pass can prove the selector is safe to branch on.
Use `--llvm-passes` to select an explicit pipeline. Add `--zig` to an LLVM build
to preprocess and link with the requested cross toolchain. The optional growth
limit compares executable sections with a matching LLVM build before
protection and aborts publication if the code size exceeds the limit.

For build automation, put the same options in a [JSON config](examples/protect.json)
and run `"$tool" protect --config path/to/protect.json`. Relative paths resolve
from the current working directory. `include`, `exclude` and `target` are arrays.

For analysis benchmarks, `--markers 200 --marker-seed 42` adds a resource of 200
deterministic hexadecimal canaries. The loader never reads it. The feature is
disabled by default.

## Benchmarks

```sh
bash benchmarks/run.sh "$tool" --clang /path/to/clang --llvm-opt /path/to/opt \
  --llvm-plugin /path/to/NativeEncode.so --profile balanced --seed 42
```

The benchmark reports median call time, checksums, JAR and library bytes.
Set `MAX_ARITHMETIC_OVERHEAD_PERCENT` to enforce an arithmetic workload budget
for the LLVM build. See [local verification](docs/verification.md) for measured
results and platform coverage.

The [Fabric Loom example](examples/fabric/README.md) accepts either a remapped
JAR or the output of a separate Java obfuscator.
