# Comparative benchmarks

Run the installed distribution with Python 3.11 or newer, JDK 21 or newer, and a C compiler.
Pass `--zig` on Windows, or when using Zig as the native compiler. The output directory must be new.

```sh
./gradlew installDist
python3 benchmarks/compare.py --include-c --output build/comparison
python3 benchmarks/test-comparison.py
```

`compare.py` runs checked JVM probes on multiple seeds and compares original and protected checksums
on every startup and steady-state run. It records the tool commands and their captured output,
build attempts and successful-build medians, artifact hashes and sizes, and JVM native method
coverage. Coverage counts native access flags from the packaged classes for the seven requested
methods. When a tool renames these methods or their owner, the coverage result is unavailable.
It also records GNU time's command peak RSS where available. This is a per-command peak rather
than the sum of process-tree memory. Reports include the samples behind each median.

The JVM lane begins with one JAR compiled once for Java 21. The C lane begins with one C11
source file and a same-host `--cc` baseline compiled at `-O2`. For startup and steady-state
checks both lanes exercise the same wrapping 32-bit arithmetic. JVM probes additionally
exercise reference, array, and exception behavior. Compare runtimes and sizes within a
lane. JNI transitions, JIT compilation, JAR packaging, and linked executable contents make
cross-lane performance and size ratios difficult to interpret.

## External adapters

Use `--adapters PATH` with a JSON array. Each entry names one pinned tool configuration.
The example uses a regular C compiler as a control.

```json
[
  {
    "name": "c-control",
    "lane": "c",
    "version": ["cc", "--version"],
    "build": ["cc", "-std=c11", "-O2", "{input}", "-o", "{output}"],
    "limits": {
      "minBuildSuccessPercent": 100,
      "maxArtifactGrowthPercent": 0
    }
  }
]
```

- `lane` is `java` or `c`. The default reference is `java-original` or `c-original`.
  Set `relativeTo` to a different case in the same lane, such as `native-obfuscator`.
  The original for that lane still determines semantic equivalence.
- The runner executes argument arrays directly in a case-specific working directory.
  Put an adapter executable on `PATH` or give its absolute path. Wrapper scripts can assemble
  multi-step compiler workflows. The `version` command should print the tool revision, compiler
  version, and any other version needed to reproduce the build.
- Supply `{input}` and `{output}` as individual arguments. The Java input is the original Java 21
  JAR and the C input is the common C11 source. Emit a runnable JAR or executable at `{output}`.
  Optional `{work}` is the case directory and `{selection}` is the exact JVM method list.
  For a Java-to-native tool, preserve the JAR's main class and apply the method selection
  to that tool's configuration. Package the native library inside the JAR.
- A C adapter should apply protection to `workload_step`, `workload_mix`, and `workload_batch`
  and preserve the timing and checksum harness. With a plugin or patched compiler, use the
  corresponding unmodified compiler version as `--cc` when available.
- Repeatable builds use the same filenames and work directory on each attempt. An adapter should
  rebuild each attempt from its input and record any cache policy. The benchmark checks the
  latest successful artifact at runtime and compares hashes from every successful build.

Radioegor146/native-obfuscator belongs in the Java lane. Its adapter needs to include JNI source
generation, platform compilation, and JAR repackaging in the timed build. Obfuscator-LLVM,
Hikari, O-MVLL, and an authorized local copy of Tigress belong in the C lane. Supply a wrapper
for the exact version and transformation settings being tested. Java coverage has no C-lane
equivalent. The runner does not bundle these tools or publish reference results for them.

`llvm-adapter.py` provides a concrete C-lane adapter for this project's plugin. Register
two cases with build commands using these arguments followed by `{input}` and `{output}`.
Use the same tool options followed by `--version` as each case's version command.

```text
python3 /repo/benchmarks/llvm-adapter.py --clang /llvm/bin/clang
python3 /repo/benchmarks/llvm-adapter.py --clang /llvm/bin/clang --opt /llvm/bin/opt --plugin /build/NativeEncode.so --passes "native-mix<seed=42;max=8;prefix=workload_>"
```

Set the protected case's `relativeTo` to the first adapter. This uses the same Clang
frontend settings and backend for both cases, with the plugin invoked between them.

## Budgets and interpretation

Use `--native-limit minNativeCoveragePercent=85` to set a budget on the built-in
native-obfuscator case. Repeat `--native-limit` to set multiple budgets. Adapters
can set the same metrics in `limits`.

Available metrics include `maxBuildMs`, `minBuildSuccessPercent`, `minNativeCoveragePercent` (Java only),
`maxArtifactGrowthPercent`, `maxStartupIncreaseMs`, `maxRuntimeOverheadPercent`, and
`maxPeakRssOverheadPercent`. The build time cap applies to the median of successful builds.
Startup uses the median elapsed process time. The runtime cap applies to each workload.
The RSS cap uses the median across steady-state forks. An unavailable required metric is a
budget violation. A nonzero exit indicates a build, semantic, or budget failure. The report
keeps failed build attempts and withholds timing comparisons for artifacts that fail execution
or checksum checks.

Startup uses fresh processes with potentially warm filesystem caches. Measuring RSS wraps
commands in GNU time and includes its launch overhead in process wall time. Treat sub-millisecond
process differences cautiously. For comparable runs, keep versions, target, CPU, JVM, input,
build settings, and sample counts fixed. Prefer multiple independent runs before adopting
machine-specific CI thresholds.

This runner does not assign reconstruction-difficulty scores. Use the existing
`analysis-resistance.py` linked-code metrics and `ghidra/` helper-recovery fixtures for
this project's analysis profiles. A comparative reconstruction study needs a common
ground truth, controlled analyst access, and a reproducible scoring procedure.
