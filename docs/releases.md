# Release verification

The release workflow is manually dispatched against an existing `vMAJOR.MINOR.PATCH`
tag, optionally with a prerelease suffix. It runs the verification workflow at
the tagged commit, builds the CLI archives, and checks the tag again before
creating a draft GitHub release. The CLI archives contain its JVM dependencies.
The release also includes the verified Fedora Linux x86-64 plugin builds for
LLVM 22 and 23. Match the compiler and LLVM package versions in the plugin
archive's `toolchain.txt`, or build against your local LLVM SDK.

Download the archive and `SHA256SUMS` from the release. Check both the
archive's contents against the recorded digest and its signed GitHub Actions
build provenance.

```sh
archive=native-obfuscator-v1.2.3.zip
grep -F "  $archive" SHA256SUMS | sha256sum --check
gh attestation verify "$archive" --repo 2q9e/native-obfuscator \
  --signer-workflow 2q9e/native-obfuscator/.github/workflows/release.yml
```

The signed provenance binds the archive digest to the producing GitHub Actions
workflow. [GitHub artifact attestations](https://docs.github.com/en/actions/concepts/security/artifact-attestations)
