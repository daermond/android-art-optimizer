#!/usr/bin/env bash
set -euo pipefail
if [ -x ./gradlew ]; then
  GRADLE=./gradlew
elif command -v gradle >/dev/null 2>&1; then
  GRADLE=gradle
else
  echo "Gradle wrapper/local Gradle missing. Generate the wrapper with Gradle 8.9 or open the project in Android Studio." >&2
  exit 2
fi
"$GRADLE" --no-daemon lint testDebugUnitTest assembleDebug
