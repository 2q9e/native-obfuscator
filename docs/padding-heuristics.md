# Archive-only padding check

`src/test/padding/ArchiveCheck.java` analyzes a final JAR without using a generated-class inventory, original input, native registration metadata, or a reserved package name. It parses every `.class` entry, including any multi-release variants, and uses actual uncompressed entry sizes after native stubbing. The JSON report includes each class's size, field count, method count, native count, and Java body count. Aggregate distributions separate classes with native methods from classes without them, with minimum, 10th percentile, median, 90th percentile, maximum, and mean values.

Compile and run it with the application's runtime libraries. From the repository root, after `installDist`, a PowerShell example is:

```powershell
New-Item -ItemType Directory -Force build/archive-check | Out-Null
javac --release 21 -cp 'build/install/native-obfuscator/lib/*' -d build/archive-check src/test/padding/ArchiveCheck.java
java -cp 'build/archive-check;build/install/native-obfuscator/lib/*' ArchiveCheck --self-test
java -cp 'build/archive-check;build/install/native-obfuscator/lib/*' ArchiveCheck final.jar > build/blind-check.json
java -cp 'build/archive-check;build/install/native-obfuscator/lib/*' ArchiveCheck final.jar local-inventory.txt > build/evaluated-check.json
```

Use the actual distribution directory name if it differs. On Unix, replace the classpath separator `;` with `:`. The optional local inventory contains one internal class name, `.class` archive path, or `owner#methodDescriptor` per line. Empty lines and lines starting with `#` are ignored. Keep this file outside release artifacts. The tool completes the blind analysis before it reads this inventory. The evaluation then reports true positives, false positives, misses, true negatives, names of false positives and misses, and inventory names absent from the archive. These are local verification inputs, with no generated-class marker shipped in the JAR.

The checker flags these recurring patterns:

| Pattern | Threshold |
| --- | --- |
| Same simple-name pattern after numeric runs and long hexadecimal runs are abstracted | At least 5 classes |
| Same method, field, native and Java counts plus sorted method descriptors | At least 8 classes |
| Same uncompressed size band, in 32-byte bands | At least 12 classes |
| Identical normalized Java bodies with constants retained | At least 5 distinct classes |
| Identical Java body skeletons with constant values erased | At least 8 distinct classes |

Body normalization preserves instruction order, local indices, branch destinations, descriptors, invocation kinds, external declaring types, and switch structure. It abstracts member names and references to the current declaring class. The second comparison also erases numeric and `ldc` constant values. This intentionally catches implementations that differ only in names or constants. Bodies shorter than six instructions, constructors, and class initializers are excluded from body comparisons to reduce universal getter and registration-stub noise. Native declarations have no Java body to compare. Abstract methods count toward total methods but do not count as Java bodies.

The thresholds are fixed heuristics, not a trained classifier. Repeated ordinary APIs, generated protocol classes, numerical utilities, and application naming conventions can produce false positives. Unusual generated classes can escape every tested heuristic. A size-band flag is evidence of clustering, not proof of padding. The checker cannot inspect normalized source bodies that have already become native, detect all duplication inside native libraries, or establish semantic utility. Report the actual flags alongside the ground-truth evaluation, including unfavorable results; do not interpret a low flag rate as universal undetectability.

The built-in self-test constructs a 15-class archive and verifies independent normalization with and without constants, the native-class distribution, and the evaluation arithmetic. Eight classes are designated generated only after analysis. Its expected result is eight true positives, six false positives, zero misses, and one true negative. Ten classes share an exact normalized body; fourteen share its constant-erased skeleton.

## Local measurements on November 21, 2026

The padding driver ran on Windows x64 with JDK 25.0.5, Zig 0.15.2, and LLVM 23.1.6. Balanced used LLVM seed 42. Both runs used padding seed 77236712 and the same 5,811-byte arithmetic input. Timings cover protection through JAR writing and reflect the local workload and compiler caches.

| Generated classes | Native methods including application methods | C seconds | C final JAR bytes | Balanced seconds | Balanced final JAR bytes |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 0 | 2 | 0.31 | 18,612 | 0.53 | 18,949 |
| 1 | 4 | 0.52 | 23,148 | 0.68 | 23,748 |
| 12 | 26 | 0.58 | 69,125 | 0.66 | 71,020 |
| 1,000 | 2,002 | 46.65 | 4,674,923 | 30.88 | 5,071,170 |

At 1,000, growth from the original was 4,669,112 bytes in C and 5,065,359 bytes in balanced. Each JAR contained exactly 1,000 generated classes beyond the padding-disabled path. The JNI runtime accounted for one additional class in every protected JAR. The 12-class fixed-seed builds matched byte for byte within each profile, and the 1,000-class inventories matched across profiles.

The 1,000-class builds packaged and structurally verified libraries and registrations for Windows x64, Windows ARM64, Linux x64, and Linux ARM64. Windows x64 executed, and the other three targets were cross-built. Every generated native method was tested with 24 inputs, for 48,000 calls per profile across range, bounded collection, and summary routines. The 487 generated native array routines each received six null inputs. Application output matched at all counts. Balanced transformed 4,380 sites in 1,776 methods per target. The other 226 methods reported zero sites.

The arithmetic archive had one genuine native-bearing peer. Generated classes had a median uncompressed size within seven bytes of that peer. The distribution below uses final class entries after JNI stubbing.

| Measure | Generated min / P10 / median / P90 / max | Genuine peer |
| --- | --- | ---: |
| Uncompressed bytes | 8,330 / 8,744 / 8,912 / 9,098 / 9,617 | 8,905 |
| Methods | 19 / 25 / 30 / 34 / 36 | 33 |
| Fields | 0 / 0 / 1 / 2 / 2 | 1 |
| Native methods | 2 / 2 / 2 / 2 / 2 | 2 |
| Java bodies | 17 / 23 / 28 / 32 / 34 | 31 |

A second balanced build combined the arithmetic, JNI coverage, and Fabric kernel fixtures, giving four genuine native-bearing peers. The complete `harden`, `verify`, and `apply` CLI workflow produced a 3,985,389-byte release from a 14,196-byte input. Hardening, including verification, took 33.85 seconds. The applied bytes matched the staged bytes. The released application matched the original on Windows x64. The other three targets were cross-built. The verifier confirmed 1,500 generated native methods plus six application methods and the full 1,000 generated class entries. The generated native-count mix was 500 classes with one and 500 with two, in the same proportions as the four native-bearing peers. Balanced recorded 3,339 transformed sites and 160 zero-site methods per target.

| Measure | Generated min / P10 / median / P90 / max | Four native-bearing peers, sorted |
| --- | --- | --- |
| Uncompressed bytes | 472 / 613 / 1,462 / 15,817 / 16,512 | 527 / 1,016 / 8,905 / 15,795 |
| Methods | 4 / 4 / 8 / 49 / 63 | 3 / 11 / 33 / 110 |
| Fields | 0 / 0 / 1 / 13 / 14 | 0 / 0 / 1 / 13 |
| Native methods | 1 / 1 / 1 / 2 / 2 | 1 / 1 / 2 / 2 |
| Java bodies | 3 / 3 / 7 / 47 / 61 | 2 / 10 / 31 / 108 |

The broader fixture shows the method-count limit of the generator. It approached the large peer's class size with fewer Java methods. Each run used only a small sample of genuine native-bearing classes.

| Blind-check fixture | Generated flagged | False positives | Generated missed | True negatives |
| --- | ---: | ---: | ---: | ---: |
| Arithmetic, 0 generated | 0 | 0 | 0 | 3 |
| Arithmetic, 1 generated | 0 | 0 | 1 | 3 |
| Arithmetic, 12 generated | 0 | 0 | 12 | 3 |
| Arithmetic, 1,000 generated | 901 | 1 | 99 | 2 |
| Four native-bearing peers, 1,000 generated | 795 | 4 | 205 | 4 |

The arithmetic flags came from repeated 32-byte size bands, including one false positive on `Arithmetic`. The broader fixture triggered 779 size-band flags and 259 class-shape flags, with overlap. Its false positives were the four genuine native-bearing peers. Neither run triggered the naming or normalized-body thresholds. These runs show that size clustering and repeated shapes remain visible at 1,000 classes. The checker sees only the class entries and the documented patterns, including Java methods that still have bodies.

To reproduce the arithmetic matrix, run `src/test/padding/verify.ps1` with a Zig executable and Gradle. Supply matching Clang, opt, and plugin paths for the balanced run. The driver writes local timings, distributions, inventories, and blind evaluations into its output directory under `build/`.
