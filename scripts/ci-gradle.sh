#!/usr/bin/env bash
set -euo pipefail

log=$(mktemp)
trap 'rm -f "$log"' EXIT
if gradle --no-daemon --console=plain "$@" 2>&1 | tee "$log"; then
    exit 0
fi
if ! grep -F 'https://repo.maven.apache.org/maven2/' "$log" | grep -F 'Received status code 429' > /dev/null; then
    exit 1
fi
echo 'Maven Central rate limited dependency downloads. Retrying via its Google Cloud mirror.'
gradle --no-daemon --console=plain -PcentralMirror "$@"
