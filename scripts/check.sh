#!/usr/bin/env bash
set -euo pipefail
if [ -x ./gradlew ]; then
  GRADLE=./gradlew
elif command -v gradle >/dev/null 2>&1; then
  GRADLE=gradle
else
  echo "Gradle wrapper/local Gradle missing. Restore the committed Gradle 9.3.1 wrapper." >&2
  exit 2
fi
"$GRADLE" --no-daemon lint testDebugUnitTest assembleDebug
