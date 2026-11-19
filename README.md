# Native Obfuscator

Native Obfuscator is a **Java and Kotlin JAR obfuscator for Minecraft Fabric mods and JVM applications**. It compiles selected JVM bytecode methods to native code through JNI and bundles the libraries in the output JAR. Optional LLVM passes transform arithmetic and control flow with a reproducible seed.

[GitHub Actions](https://github.com/2q9e/native-obfuscator/actions/workflows/verify.yml) · [Fabric Loom example](examples/fabric/README.md) · [Benchmarks](docs/verification.md) · [MIT license](LICENSE)

## Quick start

The commands below use a Linux shell, JDK 21 or 25, an installed Gradle 9.3.1, and a C11 compiler on `PATH`. Set `GRADLE_HOME` or add Gradle to `PATH` before building.

```sh
git clone https://github.com/2q9e/native-obfuscator.git
cd native-obfuscator
./gradlew installDist
```

Build the bundled arithmetic fixture, check one method, and run the protected JAR:

```sh
tool=build/install/native-obfuscator/bin/native-obfuscator
mkdir -p build/quickstart
javac --release 21 -d build/quickstart src/test/fixtures/Arithmetic.java
jar --create --file build/quickstart.jar --main-class Arithmetic -C build/quickstart .

"$tool" analyze --input build/quickstart.jar --include 'Arithmetic#calcInt(III)I'
"$tool" protect --input build/quickstart.jar --output build/quickstart-protected.jar \
  --include 'Arithmetic#calcInt(III)I'

java --enable-native-access=ALL-UNNAMED -jar build/quickstart-protected.jar
```

The protected fixture prints `55cfd63f18498e67`, the same checksum as the original.

## Protect your own JAR

Run `analyze` first to see which methods can be converted. Use the names and descriptors from the final input JAR, especially if a build step renamed or remapped the classes.

```sh
javap -p -s -classpath input.jar my.mod.Math
"$tool" analyze --input input.jar --include 'my/mod/Math#mix(II)I'
"$tool" protect --input input.jar --output protected.jar \
  --include 'my/mod/Math#mix(II)I'
```

- Select methods as `internal/Class#method(descriptor)`. Repeat `--include` or `--exclude` to combine patterns. Quote patterns containing `*` or `?`.
- The default policy fails if a selected method uses unsupported bytecode. Use `--unsupported retain` to leave those methods in Java. The CLI reports each decision as JSON.
- Protect an unsigned input JAR, then sign the output if your release process requires signing. Use `--emit-c` to inspect the generated C.
- For repeatable builds, save the options in a [JSON configuration](examples/protect.json) and run `"$tool" protect --config protect.json`. Relative paths resolve from the working directory.

## Optional file padding

Interactive `protect` runs ask whether to add 12 standalone utility class files. Press Enter to skip padding. Use `--padding on` or `--padding off` to choose in advance. Non-interactive builds default to off. In JSON configuration, set `"padding": "on"` or `"padding": "off"`.

The classes contain working routines such as binary search, checksums, and moving averages. They use 128-character ASCII class and method names and omit debug metadata. The protected JAR stores them as resources outside its application classpath, so the application does not load or execute them. Padding increases the size of the output JAR.

## Minecraft Fabric

Run Native Obfuscator on the JAR after Fabric Loom's `remapJar` task. If you also use a Java bytecode obfuscator, pass its remapped output to Native Obfuscator and select methods by their final names. Start with internal numeric methods. Fabric entrypoints and classes declared in Mixin, language-adapter, and access-widener metadata are excluded from conversion.

The [Fabric 1.21.11 example](examples/fabric/README.md) includes a `protectFabricJar` Gradle task and a bootstrap test that exercises the generated JNI code, a Mixin accessor, and an access widener. Place the protected JAR in your Fabric instance's `mods` directory.

## Native targets

| Platform | x64 | ARM64 |
| --- | --- | --- |
| Linux | `x86_64-linux` | `aarch64-linux` |
| Windows | `x86_64-windows` | `aarch64-windows` |

A default Linux build targets the host. Use [Zig 0.15.2](https://ziglang.org/download/) to select another target or bundle all four libraries:

```sh
"$tool" protect --input input.jar --output protected.jar \
  --include 'my/mod/Math#mix(II)I' --target all --zig /path/to/zig
```

At runtime the loader selects the library for the current OS and CPU architecture and checks its checksum. End users need a compatible JVM. On Java 25, enable native access with `--enable-native-access=ALL-UNNAMED` for classpath launches or name the module containing the loader for modular launches.

## LLVM obfuscation

Build the [LLVM 23 pass plugin](llvm/README.md) with the same LLVM version as `clang` and `opt`. The default `compatibility` profile builds JNI code with standard optimization. `balanced` adds arithmetic substitutions, while `aggressive` and `maximum` also attempt equivalent conditional paths.

```sh
"$tool" protect --input input.jar --output protected.jar \
  --include 'my/mod/Math#mix(II)I' \
  --clang /path/to/clang --llvm-opt /path/to/opt --llvm-plugin /path/to/NativeEncode.so \
  --profile balanced --seed 42 --max-growth 25
```

`--max-growth` caps executable code growth as a percentage of the matching LLVM build baseline. Use `--llvm-passes` to specify a custom pipeline. The [benchmark results](docs/verification.md) include code size, JNI overhead, and checks that transformations survive compilation.

## Bytecode support

The translator handles primitive numeric instructions, branches, loops, switches, primitive returns, and reference identity checks in static and instance methods. Constructors, interface methods, calls, field access, array operations, reference returns, and exception handlers stay in JVM bytecode. The tool preserves the input classfile version and Java names used by callers. It generates 128-character internal native function names with a `method_` prefix.

GitHub Actions runs Java 21 and 25 differential tests, Kotlin tests, and JNI runtime checks on all four targets. See the [verification report](docs/verification.md) for the results and the scope of the Fabric smoke test. For local differential tests, set `JAVA_HOME` and `JAVA_RELEASE` to the matching JDK and run `bash src/test/integration.sh "$tool"`. Set `GRADLE_HOME` to Gradle 9.3.1 to run `bash src/test/kotlin/verify.sh "$tool"`.

The project is [MIT-licensed](LICENSE). See the [dependency inventory](docs/dependencies.md) for the licenses packaged with the CLI.
