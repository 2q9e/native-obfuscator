#!/bin/sh
set -eu

if [ -n "${GRADLE_HOME:-}" ]; then
    exec "$GRADLE_HOME/bin/gradle" "$@"
fi
if command -v gradle >/dev/null 2>&1; then
    exec gradle "$@"
fi

printf '%s\n' 'Install Gradle 9.3.1 and set GRADLE_HOME or add its bin directory to PATH.' >&2
exit 127
