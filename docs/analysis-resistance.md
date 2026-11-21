# Analysis resistance

Use `--analysis-resistance balanced` to package synthetic resources and bind their manifest to the native registration path. `maximum` uses a larger resource set and can outline a bounded set of native helpers. The default `off` profile uses the legacy build and registration path.

```sh
"$tool" protect --input input.jar --output protected.jar \
  --include 'app/Kernel#mix*' --analysis-resistance balanced --analysis-seed 42 \
  --analysis-resource-budget 4096 --analysis-entry-budget 8
```

The equivalent `protect.json` uses the camel-case option names. Paths resolve from the working directory. Run `"$tool" protect --config protect.json`.

```json
{
  "input": "input.jar",
  "output": "protected.jar",
  "include": ["app/Kernel#mix*"],
  "analysisResistance": "balanced",
  "analysisSeed": "42",
  "analysisResourceBudget": 4096,
  "analysisEntryBudget": 8
}
```

## Resource limits

The defaults also serve as the maximum budget values for each enabled profile. Actual sizes include `manifest.bin` and describe uncompressed JAR entries.

| Profile | Default byte budget | Default entry budget | Actual resources at defaults |
| --- | ---: | ---: | --- |
| `balanced` | 262,144 | 32 | 2,296 bytes across 5 entries |
| `maximum` | 1,048,576 | 128 | 26,940 bytes across 13 entries |

The minimum budgets are 256 bytes and 2 entries. At those settings the generator emits one shortened report and its manifest, totaling 244 bytes. A budget caps resource bytes and entries separately. It counts the synthetic garden reports, inventory TSVs, and manifest. Each analysis entry has a separate 32 KiB limit. Staged verification checks these limits from ZIP metadata before reading the resource bytes. Class files, native libraries, padding, markers, and ZIP overhead have separate costs. The JAR also has a 128 MiB per-entry limit, a 384 MiB expanded-size limit, and a 30,000-entry limit.

`--analysis-seed` accepts an unsigned 64-bit decimal value and defaults to `0`. Use the same tool version, profile, seed, and budgets to reproduce the corpus. Analysis seed and budgets require an enabled profile. The analysis seed is independent of the LLVM `--seed`, padding seed, and marker seed.

Enable padding with `--padding-count 12 --padding-seed 17` and markers with `--markers 3 --marker-seed 23`. Either can be combined with an analysis profile or used on its own. The analysis corpus is generated independently of the input JAR, padding classes, and markers. An analysis-enabled JAR build requires at least one supported application method. [Utility class padding](padding.md)

## Native helpers in maximum

`maximum` can move one wrapping operation into a native helper for each of up to eight selected application methods with a supported straight-line `int` or `long` return expression. The CLI reports why methods fall outside this subset. `--profile` selects the LLVM optimization pipeline independently of the analysis profile.

For each queued helper and target, the checker uses `llvm-objdump` on the stripped library to find a direct call or tail branch to the helper that is statically reachable from the translated method entry. It resolves the tool from `LLVM_OBJDUMP`, alongside the configured `clang`, or from `PATH`, in that order. A missing tool, a missing expected helper, or an unverifiable edge fails the corresponding build or structural verification. The check establishes a linked edge from the generated code section. It does not resolve JNI method-table pointers or measure reverse-engineering effort. The LLVM code-growth limit continues to compare the protected LLVM build with its matching frontend baseline. The helper count and section limits apply independently.

## Stage, verify, and promote

`analyze` and `harden --dry-run` preview method eligibility and the resource plan in read-only mode. For a release, repeat the original JAR, selection, targets, and all analysis, padding, and marker settings on `harden`, `verify`, and `apply`.

```bash
options=(--include 'app/Kernel#mix*' --analysis-resistance balanced --analysis-seed 42)
"$tool" harden --input input.jar --output staged.jar "${options[@]}"
"$tool" verify --input input.jar --staged staged.jar "${options[@]}"
"$tool" apply --input input.jar --staged staged.jar --output release.jar \
  --state-dir release-state --confirm-overwrite "${options[@]}"
"$tool" restore --output release.jar --state-dir release-state
```

`harden` replaces the staging path only after the new JAR passes verification. `apply` verifies it again before promotion. `--confirm-overwrite` allows replacement of an existing release. `restore` uses the same state directory to undo that promotion. Run your application tests against both the original and staged JARs before deploying. [Staged release workflow](../README.md#staged-release-workflow)

## Integrity and compatibility

The manifest and corpus live under `META-INF/native-obfuscator/<id>/analysis/`. Structural verification regenerates the expected corpus from trusted build options and checks the exact entry set and contents. It checks the versioned JNI registration export and the resource manifest digest in each bundled target library. These checks detect changes while the validator and registration code remain intact. A party able to replace both the JAR and library can also replace their checks. Release authenticity depends on how the artifact is authenticated and distributed.

At runtime, Java checks the manifest and verifies the listed resources against their recorded lengths and SHA-256 digests. JNI registration then requires the manifest digest to match the value embedded in the loaded native library. A missing or modified listed resource or a digest mismatch prevents the affected native registration. Each runtime class validates the corpus once before loading the native library, and the native library compares the Java-supplied digest during registration. The normal library checksum and registration metadata checks also apply.

Analysis-enabled JARs use Java 21 or later and the existing Windows and Linux x64 and ARM64 native targets. On Java 25, enable native access as you would for any protected JAR. `protect --emit-c` is a source inspection workflow. Runtime binding requires the packaged JAR. [Native targets](../README.md#native-targets)
