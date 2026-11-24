# Fabric fixture

This example targets Minecraft 1.21.11, Yarn `1.21.11+build.4`, Loom `1.14.10`, and Fabric Loader `0.18.4`. The mod requires Java 21 or later and has no Fabric API dependency. At initialization it checks the integer kernel, an identifier score that reads Minecraft's widened path field, and a comparison that invokes Minecraft's `Identifier` through `Comparable`. It checks native recovery when an identifier is absent and exercises a Mixin accessor on the same identifier. The registry-ranking loop carries an `Identifier` across iterations, skips null and repeated references, and reads the widened path field of each accepted candidate. Fabric provides the version-specific [Loom documentation](https://docs.fabricmc.net/1.21.11/develop/loom/) and [1.21.11 release guidance](https://fabricmc.net/2025/12/05/12111.html).

## Build and protect

From the native-obfuscator repository root, use JDK 21 or 25 and install Gradle 9.3.1. A default native build targets the Linux host with its local C compiler. Zig is required for the optional cross-build.

```sh
./gradlew installDist
./gradlew -p examples/fabric protectFabricJar
```

The example is a standalone Gradle project. `protectFabricJar` uses Loom's `remapJar` output by default and converts `Kernel.mix`, `Kernel.identifierScore`, `Kernel.identifierPriorityOrDefault`, and `Kernel.rankRegistryCandidates`. Supply `-PnativeHierarchyJar=/absolute/path/to/server-intermediary.jar` to also convert `Kernel.identifierPriority`. The selection keeps the entrypoint and Mixin in Java. The output is `examples/fabric/build/libs/native-fabric-fixture-1.0.0-protected.jar`. Gradle logs the CLI's method decisions and compiler errors.

Use these Gradle properties when the corresponding input differs:

| Property | Value |
| --- | --- |
| `nativeInputJar` | Absolute path to the existing Java obfuscator's output. Start from Loom's remapped JAR and preserve intermediary mappings, Fabric metadata, mixin configuration and access-widener resources. |
| `nativeMethod` | Optional final-name selector. Use the Java obfuscator's mapping to select renamed methods. |
| `nativeHierarchyJar` | Absolute path to Minecraft's intermediary server JAR. Its class headers prove that the runtime `Identifier` implements `Comparable`. |
| `nativeCli` | Absolute path to an alternate native-obfuscator executable. The default uses the root `installDist` Java libraries. |
| `nativeTarget`, `nativeZig` | A target such as `aarch64-linux` or `all`, and the absolute path to a Zig executable for cross-compilation. |

```sh
./gradlew -p examples/fabric protectFabricJar \
  -PnativeInputJar=/absolute/path/to/post-java-obfuscation.jar \
  -PnativeHierarchyJar=/absolute/path/to/server-intermediary.jar \
  '-PnativeMethod=example/nativefixture/a#a*'
```

The [ProGuard keep rules](smoke/proguard.pro) preserve the Fabric entrypoint and Mixin names while allowing the kernel and its methods to be renamed. Build Loom's remapped mod first. Use the intermediary server JAR produced by Fabric Loader and supply the server libraries, Fabric classpath, and JDK modules as ProGuard library inputs. Generate a library overlay with the remapped mod's access widener so that ProGuard can resolve the widened field. The overlay is used during this test's ProGuard invocation. [ProGuard configuration manual](https://www.guardsquare.com/manual/configuration/usage)

```sh
# class_tweaker_cp contains Fabric class-tweaker 0.3.0 and the ASM 9.9 modules.
javac --release 21 -cp "$class_tweaker_cp" -d "$work" examples/fabric/smoke/ProGuardLibrary.java
java -cp "$work:$class_tweaker_cp" ProGuardLibrary \
  "$remapped_mod" "$intermediary_server" "$work/widened-library.jar"
java -jar "$proguard_jar" @examples/fabric/smoke/proguard.pro \
  -injars "$remapped_mod" -outjars "$work/post-proguard.jar" -printmapping "$work/mapping.txt" \
  -libraryjars "$work/widened-library.jar" \
  -libraryjars "$intermediary_server(!net/minecraft/class_2960.class;!module-info.class)" \
  "${library_args[@]}"
```

Set the Bash array `library_args` to alternating `-libraryjars` and absolute library paths. Include the JDK's `jmods/*.jmod` with the filter `(!**.jar;!module-info.class)`, the Fabric Loader and its dependencies, and the libraries extracted by the Minecraft server bundle. Use the output mapping for the Native Obfuscator selection. ProGuard 7.8.2 named the fixture class `example.nativefixture.a` and overloaded the five methods as `a`. The selector above selected all five with `nativeHierarchyJar` configured. The renamed native methods returned the expected values through Fabric Loader 0.18.4 on Windows x64 with Java 21 and 25.

To check that the kernel is native and the access widener uses the runtime namespace:

```sh
fixture_jar=examples/fabric/build/libs/native-fabric-fixture-1.0.0-protected.jar
javap -p -classpath "$fixture_jar" example.nativefixture.Kernel
unzip -p "$fixture_jar" native-fixture.accesswidener
```

Expect native declarations for `mix`, `identifierScore`, `identifierPriorityOrDefault`, and `rankRegistryCandidates` and the `intermediary` access-widener header, with the widened `Identifier` field mapped to `class_2960.field_13355`.

## Run on Minecraft

In a Minecraft 1.21.11 Fabric Loader 0.18.4 test server whose operator has accepted the Minecraft EULA, install the protected JAR in `mods/`. Use the [official Fabric server launcher](https://fabricmc.net/use/server/), with the original fixture JAR removed from that server's mods. Run the installed launcher, substituting its filename if it differs.

```sh
java --enable-native-access=ALL-UNNAMED -jar fabric-server-launch.jar nogui
```

Look for `Native fixture passed (kernel = 997305193, identifier score = 28443952, priority = 1030, fallback = -1, registry = 1115680, Mixin and access widener active)` in the server log. Wait for the Minecraft world to finish loading, enter `stop` in the server console, and confirm that it exits. The repository and build tasks do not accept Minecraft's EULA.

## Offline bootstrap smoke test

The smoke script exercises the real publisher server JAR in Fabric's production mappings. Its first process starts the Minecraft command-line entrypoint through Fabric Loader with `--help`. Its second process initializes the production Fabric class loader and invokes Fabric's dedicated-server mod initialization callback under `-Xcheck:jni`. This checks actual native execution, mixin application, and access widening before world startup.

On Linux, supply the original Minecraft 1.21.11 server bundle and a colon-separated classpath of absolute paths to the Fabric Loader `0.18.4`, `intermediary:1.21.11:v2`, `sponge-mixin:0.17.0+mixin.0.8.7`, and ASM `9.9` JARs. The ASM modules are `asm`, `asm-analysis`, `asm-commons`, `asm-tree`, and `asm-util`. Use the original bundle `minecraft-server.jar` from the Loom cache or the server download from Minecraft.

```sh
examples/fabric/smoke/check-bootstrap.sh "$fixture_jar" \
  /absolute/path/to/minecraft-server.jar "$fabric_classpath"
```

Set `NATIVE_FIXTURE_EXPECT_HIERARCHY=1` when testing the unrenamed fixture built with `nativeHierarchyJar` to require a native `identifierPriority` declaration as well. The script uses a temporary working directory and does not start a world. Neither smoke process creates or changes an EULA file.
