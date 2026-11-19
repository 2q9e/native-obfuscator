# Native Obfuscator

Native Obfuscator converts explicitly selected JVM methods in an existing JAR to JNI functions. It accepts the final JAR produced by a Java obfuscator or Fabric Loom and bundles the native libraries. The input JAR is analyzed as data and its classes are never loaded by the CLI.

## Build and use

Use JDK 21 or 25, Gradle 9.3.1, and a Linux C11 compiler for a host build. Use Zig 0.15.2 for the four bundled targets.

```sh
gradle installDist
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

Use the matching JDK in `JAVA_HOME` for each test.

See the [reference audit](docs/reference-audit.md) for architectural provenance and licensing.
