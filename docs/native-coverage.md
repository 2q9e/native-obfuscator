# JNI coverage and verification

Native conversion checks operand types before emitting JNI calls. Frame analysis preserves exact array and object descriptors and checks assignments it can prove from bytecode and descriptors. A method stays in Java under `--unsupported retain` when the translator cannot prove a required assignment. The default policy fails the build. Diagnostics identify the instruction and reason.

The verified additions are:

| Operation | Accepted slice | Semantics checked |
| --- | --- | --- |
| Array length | Proven primitive/reference array descriptors | Null checks and length |
| Array loads and stores | All eight primitive element types, reference elements, and arrays containing arrays, with an exact descriptor | Signed byte/short extension, unsigned char extension, boolean low-bit stores, null before bounds before reference-store compatibility, category-2 values |
| Fields | Static and instance fields of primitive or reference type declared by the translated method's class | Null receivers, narrow values, reference identity, final-write rejection, descriptors and staticness |
| Direct calls | Static calls, virtual dispatch, and private calls within the translated method's class | Null receivers, virtual overrides, private dispatch on subclass receivers, primitive ABI categories, reference arguments and results used within the method, exception propagation |
| Cross-class static calls | JVM linkage bridges for external static class and interface methods | Lazy initialization of the resolved declaring class, nestmate access, missing methods, exception propagation, full JVM int-stack arguments |
| Cross-class instance calls | External virtual and interface dispatch with a proven receiver type | Overrides, interface defaults, null receivers, and resolution errors before null checks |
| Cross-class fields | Static and instance field reads and writes in JVM linkage bridges | Lazy initialization, nestmate access, reference identity, volatile JVM operations, and resolution errors before null checks |
| Reference equality | Existing JNI identity comparison | Distinct JNI handles referring to the same Java object compare equal |

Every JNI lookup or potentially throwing operation checks for a pending exception before execution continues. Temporary class handles are released on success and on failed member lookup. Reference results from array loads, field loads, and calls live for the native invocation. The translator rejects methods that combine a backward branch with a reference-producing operation to bound local-reference accumulation. For acyclic methods that may need more than JNI's guaranteed 16 local slots, it reserves capacity with `EnsureLocalCapacity` and propagates allocation failure. A differential fixture performs 64 reference loads in one invocation. Primitive array loops remain supported. Category-aware stack duplication covers long and double array and field compound assignments. POP, POP2, and SWAP check operand categories explicitly.

The unsupported policy applies to non-private super dispatch, constructors, object and array allocation, casts and `instanceof`, explicit throws and monitors, dynamic calls, same-method exception handlers, reference returns, and same-class volatile field operations. Narrow JNI call arguments must be proven to fit their descriptors. An ASM fixture passes 257 to a Java method with a byte parameter and observes 257 in its Java body. The translator retains the caller to preserve that result. The native entry ABI assumes ordinary descriptor-range arguments from its Java callers.

External calls and fields execute their original JVM instruction in a private synthetic method in the caller class. The bridge remains visible in bytecode and adds a Java stack frame. JNI passes narrow primitive arguments as full stack integers to these bridges.

Exception tests compare exception classes and observable state, including side effects after a failed JNI operation. Bounds errors include the index and length. Helpful null-pointer and array-store messages and JVM stack frames can differ after conversion.

Run the standalone differential suite against an installed distribution:

```text
python src/test/native-coverage/verify.py build/install/native-obfuscator --work build/native-coverage --zig /path/to/zig --target all
```

Additional native build flags, including `--profile`, `--seed`, and the matching LLVM tool paths, are forwarded to `protect`. The optional work directory preserves the original/protected JARs, Java outputs, compiler diagnostics, actual native declarations, operand-check output, and a JSON result. Keep these local artifacts outside release archives.

On November 21, 2026, the suite passed using JDK 25.0.5 and Java 21 classfiles, Zig 0.15.2, and LLVM 23.1.6. Both the compatibility C path and balanced LLVM path with seed 42 converted 32 methods. Each build matched 126 result, exception, and state lines, including 34 array-bounds, 17 null-pointer, one array-store, and one arithmetic exception, as well as a checksum over 20,000 randomized native calls. Eight malformed operand, stack-category, field, and call cases were rejected before compilation. The suite checks actual native declarations and selection diagnostics and enables `-Xverify:all` and `-Xcheck:jni`. The compatibility run also exercised a Turkish JVM locale.

All four supported targets were built in both runs. Windows x64 executed. Linux x64, Linux ARM64, and Windows ARM64 were cross-built. A separate Windows x64 compatibility run matched all 126 outcomes on JDK 21.0.12. The balanced pass transformed eight sites across six of the 32 methods on each target. The other 26 methods reported zero sites. The local Windows opt driver linked the checked-in pass statically because the installed LLVM distribution lacks dynamic plugin support. Windows dynamic plugin loading remains unverified.
