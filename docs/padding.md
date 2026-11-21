# Utility class padding

Padding adds JVM classes with utility bodies before the native plan is finalized. Existing application code does not call them. Each generated class must contribute at least one converted native method through the same C or LLVM pipeline and target compiler as application methods. LLVM diagnostics identify the JVM method, target, pass, and number of transformed sites, including zero.

```
native-obfuscator harden --input input.jar --output staged.jar --include 'app/Kernel#mix*' --padding-count 12 --padding-seed 42
native-obfuscator verify --input input.jar --staged staged.jar --include 'app/Kernel#mix*' --padding-count 12 --padding-seed 42
native-obfuscator apply --input input.jar --staged staged.jar --output release.jar --include 'app/Kernel#mix*' --padding-count 12 --padding-seed 42
```

Repeat target, selection, padding, and any `--markers` or `--marker-seed` options for verification and promotion. The verifier derives expected generated entries from the original input and build options. It verifies class content after JNI stubbing, checks registration indices and metadata, validates each library's architecture and checksum, and rejects unexpected entries and bindings. Optional marker resources must match the trusted count and seed. Run the original and protected applications to check runtime equivalence.

`--padding-count` accepts integers from 0 through 1,000. `--padding on` defaults to 12 when no count is supplied. `--padding off` means zero. When both options are supplied they must agree on whether padding is enabled. Non-interactive builds default to zero. JSON settings are `paddingCount` and `paddingSeed`. The legacy `padding` setting accepts `on` and `off`.

The signed 64-bit padding seed defaults to zero and is independent of the unsigned LLVM seed. Reproducing the generated class set requires the same original JAR, tool version, application method selection, padding count, and padding seed. The `verification.expectedGeneratedClasses` array on the final JSON line from `verify` contains the derived entry names. To make a local inventory for the archive checker, save those names one per line.

The generator samples the input's native-bearing peers when available and estimates size after the same JNI rewrite used in the build. Evaluate the final uncompressed class entries by comparing size, fields, methods, and the mix of native declarations and Java bodies. A narrow pool of genuine peers can produce visible size clustering. [Archive-only heuristics and inventory format](padding-heuristics.md)
