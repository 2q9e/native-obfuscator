# Analysis resistance

Use `--analysis-resistance balanced` to add a small set of native utility methods, outline eligible operations, and bind the resource manifest to native registration. `maximum` enables larger helper graphs and utility sets. Both profiles can extend the preset LLVM pipeline when a toolchain is configured. The default `off` profile uses the legacy build and registration path.

```sh
"$tool" protect --input input.jar --output protected.jar \
  --include 'app/Kernel#mix*' --analysis-resistance balanced --analysis-seed 42 \
  --analysis-resource-budget 4096 --analysis-entry-budget 8 \
  --analysis-helper-methods 2 --analysis-helpers-per-method 1 --analysis-decoy-methods 2
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
  "analysisEntryBudget": 8,
  "analysisHelperMethods": 2,
  "analysisHelpersPerMethod": 1,
  "analysisDecoyMethods": 2
}
```

## Profile limits

| Maximum budget | Balanced | Maximum |
| --- | ---: | ---: |
| Uncompressed resource bytes | 262,144 | 1,048,576 |
| Resource entries | 32 | 128 |
| Application roots with helpers | 4 | 8 |
| Helpers per application root | 1 | 3 |
| Generated utility methods | 4 (2 by default) | 16 (6 by default) |
| Extra LLVM sites per function | 2 | 8 |

Use `--analysis-helper-methods`, `--analysis-helpers-per-method`, `--analysis-decoy-methods`, and `--analysis-llvm-sites` to reduce the profile limits. Setting either helper option to zero without supplying the other disables outlining. Setting the utility count to zero leaves existing padding settings independent. LLVM, helper, and utility limits bound work structurally. Their runtime cost depends on the selected methods and the workload.

The minimum budgets are 256 bytes and 2 entries. At those settings the generator emits one shortened report and its manifest, totaling 244 bytes. A budget caps resource bytes and entries separately. It counts synthetic reports, inventories, the optional utility catalog, and the manifest. The catalog records generated method prototypes, class fingerprints, and included sample-document paths. The plan omits the catalog when the resource budget is too tight and reports that outcome. Each analysis entry has a separate 32 KiB limit. Staged verification checks these limits from ZIP metadata before reading the resource bytes. Class files, native libraries, padding, markers, and ZIP overhead have separate costs. The JAR also has a 128 MiB per-entry limit, a 384 MiB expanded-size limit, and a 30,000-entry limit.

`--analysis-seed` accepts an unsigned 64-bit decimal value and defaults to `0`. Use the same tool version, profile, seed, and budgets to reproduce the corpus. Analysis seed and budgets require an enabled profile. The analysis seed is independent of the LLVM `--seed`, padding seed, and marker seed.

Enable padding with `--padding-count 12 --padding-seed 17` and markers with `--markers 3 --marker-seed 23`. Either can be combined with an analysis profile or used on its own. The report and inventory data streams are independent of the input JAR, padding classes, and markers. Utility classes use ordinary classpath entries and reserve existing and Fabric-declared class names. An analysis-enabled JAR build requires at least one supported application method. [Utility class padding](padding.md)

## Native helpers and LLVM

An eligible straight-line `int` or `long` method can outline its final wrapping operation. Maximum also traces returned values through locals and control-flow joins in methods with up to 64 bytecode instructions. Each reachable normal return must have the same producer, either the wrapping operation itself or a call that consumes its result. The operation needs a runtime input. Methods with exception handlers or switch instructions are excluded from this tracing. Deeper chains compute over disjoint portions of an operand and compose the partial results using unsigned word arithmetic. The analysis seed controls partitioning and argument order. The helpers use local values and no shared state. Method diagnostics identify eligibility and skip reasons.

The analysis seed assigns independent section IDs to roots and helpers. The compiler orders helper definitions by those IDs across all roots. Root and helper section prefixes remain visible in stripped libraries. The linked verifier uses the complete plan to check each edge.

When a plan contains helpers, `llvm-objdump` verifies each direct call or tail branch across the helper chain in the stripped library. Tool discovery uses `LLVM_OBJDUMP`, the configured Clang directory, then `PATH`. Missing tools, sections, or verified edges fail the corresponding build or staged verification. Structural evidence covers section entry reachability. Runtime equivalence is checked by executing protected fixtures.

`--profile` still selects the LLVM presets. With a complete LLVM toolchain, balanced adds `native-mix` to the preset. Maximum reserves one site per function for `native-route` and, when `--analysis-llvm-sites` is at least three, one for `native-compare`. The remainder of that per-function budget goes to `native-mix`. Passes supplied through `--llvm-passes` retain full control of the pipeline. Rebuild the plugin when updating to this version of the extended presets. Builds without LLVM report that status and still apply their configured native helper and resource mechanisms. [LLVM passes](../llvm/README.md)

When LLVM and an analysis profile are enabled, the build compares the executable sections against a matching LLVM frontend baseline. The report contains both executable fingerprints and sizes. The `targets[].llvmPasses` array records each plugin-reported pass invocation in execution order with its candidate, eligible, and transformed site counts, including zero. `--max-growth` enforces a caller-supplied percentage cap against the executable baseline. Fingerprint differences establish changed code for the pipeline as a whole. IR rewrite counts and individual linked helper edges have separate meanings.

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

`harden` replaces the staging path only after the new JAR passes verification. `apply` verifies it again before promotion. `--confirm-overwrite` allows replacement of an existing release. `restore` uses the same state directory to undo that promotion. Run your application tests against both the original and staged JARs before deploying. [Staged release workflow](usage.md#staged-release-workflow)

## Integrity and compatibility

The manifest and corpus live under `META-INF/native-obfuscator/<id>/analysis/`. Structural verification regenerates the expected corpus from trusted build options and checks the exact entry set and contents. It checks the versioned JNI registration export, the manifest digest, the indexed registration digest table, and the expected executable utility sections in each target library. These checks detect changes while the validator and registration code remain intact. A party able to replace both the JAR and library can also replace their checks. Release authenticity depends on how the artifact is authenticated and distributed.

At runtime, Java checks the manifest and verifies the listed resources against their recorded lengths and SHA-256 digests. JNI registration then requires the manifest digest to match the value embedded in the loaded native library. A missing or modified listed resource or a digest mismatch prevents the affected native registration. Each runtime class validates the corpus once before loading the native library, and the native library compares the Java-supplied digest during registration. The loader checks the library checksum and the registration code compares the class's metadata digest against its indexed entry in the mapped native table. Verification expects the artifact format and configuration used by the current builder. Use the matching earlier tool version for previously staged analysis-enabled JARs.

Analysis-enabled JARs use Java 21 or later and the existing Windows and Linux x64 and ARM64 native targets. On Java 25, enable native access as you would for any protected JAR. `protect --emit-c` is a source inspection workflow. Runtime binding requires the packaged JAR. [Native targets](usage.md#native-targets)

## Measurements

`benchmarks/analysis-resistance.py` compares original JVM execution and the three native profiles. It records runtime checksums, elapsed time samples, executable sizes, linked control-flow instructions, helper evidence, visible utility sections, symbol exposure, and literal recall. ARM64 reports include a separate count for constants reconstructed from adjacent 32-bit move-wide instructions to the same register. Run it after `gradlew installDist`. The default run measures the host, and repeated `--target` options add cross-builds. It writes a JSON summary and disassembly under `build/`. Set `--max-runtime-overhead-percent` or `--max-startup-overhead-ms` to fail the benchmark when fixture medians exceed your limits relative to the native `off` build. These thresholds are specific to the benchmark workload and host. Reconstruction difficulty across different analysts or tools requires separate evaluation.

### Headless decompiler recovery

The optional Ghidra benchmark evaluates direct helper calls on project-generated stripped libraries. Extract the libraries from a staged JAR with verified helpers into a directory using the benchmark filenames, for example `x86_64-linux-libprotected.so` or `aarch64-windows-protected.dll`. Keep the corresponding `verify` JSONL output. Run the script from an installed Ghidra distribution using its `analyzeHeadless` launcher.

```bash
mkdir -p "$work/ghidra-projects"
analyzeHeadless "$work/ghidra-projects" helper-fixture \
  -import "$work/stripped-libraries" -recursive -readOnly \
  -scriptPath "$PWD/benchmarks/ghidra" -postScript MeasureHelperRecovery.java > "$work/ghidra.log" 2>&1
python3 benchmarks/ghidra/score-recovery.py "$work/staged.jar" "$work/verify.jsonl" \
  "$work/ghidra.log" > "$work/helper-recovery.json"
```

The `auto` phase decompiles the functions Ghidra discovered. The `section-entry` phase also supplies entry points from visible helper and root sections. The scorer checks SHA-256 digests for the JAR and each imported binary before comparing the decompiler's high-level direct calls with the linked verifier's expected edges. It reports exact matches, missed edges and additional helper calls. Tail transfers can appear differently in the decompiled graph. These results measure entry-point discovery and direct-call recovery. Recovery of arithmetic and algorithms requires separate measurements.
