#!/usr/bin/env bash
# The gate every phase has to pass (SPEC 17). The build-failing rule checks
# (merged manifest, banned dependencies, source rules) are wired into these tasks.
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew testDebugUnitTest lintDebug assembleDebug "$@"
