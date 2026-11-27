# Release verification

Pushing an existing source commit with a `vMAJOR.MINOR.PATCH` tag starts the
release workflow. Tags accept SemVer prerelease and build metadata. A manual
dispatch on a tag creates a draft by default. The workflow runs the full
verification matrix, builds the versioned CLI archives, generates release notes
and an SPDX inventory, and verifies signed attestations before creating the
release.

On Linux x64 or ARM64, extract the TAR archive and run
`native-obfuscator/bin/native-obfuscator --version`. On Windows x64 or ARM64,
extract the ZIP archive and run
`native-obfuscator\bin\native-obfuscator.bat --version`. Both archives contain
the same Java application and dependencies. Use JDK 21 or 25 with its JNI
headers, and use Zig for Windows builds or cross-compilation. See the
[native target options](../README.md#native-targets).

The release workflow packages the tested Fedora Linux x64 and ARM64 LLVM 22 and 23
plugins. Match the compiler and LLVM versions recorded in `toolchain.txt`.
For another supported host, [build the plugin](../llvm/README.md) with that
host's matching LLVM SDK.

Download the archive and `SHA256SUMS` from the release. Check both the
archive's contents against the recorded digest and its signed GitHub Actions
build provenance.

```sh
archive=native-obfuscator-v1.2.3.zip
grep -F "  $archive" SHA256SUMS | sha256sum --check
gh attestation verify "$archive" --repo 2q9e/native-obfuscator \
  --signer-workflow 2q9e/native-obfuscator/.github/workflows/release.yml
gh attestation verify "$archive" --repo 2q9e/native-obfuscator \
  --signer-workflow 2q9e/native-obfuscator/.github/workflows/release.yml \
  --predicate-type https://spdx.dev/Document/v2.3
```

The signed provenance binds the archive digest to the producing GitHub Actions
workflow. [GitHub artifact attestations](https://docs.github.com/en/actions/concepts/security/artifact-attestations)

To reproduce the CLI archives and SPDX inventory, check out the tag and use
Gradle 9.3.1, Python 3.11 or newer, and the exact JDK build recorded in
`BUILD-TOOLS.txt`. Gradle validates the resolved dependency hashes. The archive
entries are sorted with normalized timestamps, and the SPDX creation timestamp
comes from the source commit.

```bash
tag=v1.2.3
version=$(python3 scripts/release_assets.py version "$tag")
./gradlew "-PreleaseVersion=$version" distZip distTar
python3 scripts/release_assets.py package --tag "$tag" \
  --epoch "$(git show -s --format=%ct HEAD)" --repository 2q9e/native-obfuscator \
  --zip build/distributions/native-obfuscator.zip --tar build/distributions/native-obfuscator.tar \
  --metadata gradle/verification-metadata.xml --output build/reproduced-release
```

Compare the resulting hashes with the published checksums. The SPDX document
covers the Java dependencies shipped in the CLI archives. LLVM plugin archives
are separately attested, with their compiler and LLVM package versions recorded
in their toolchain inventories.
