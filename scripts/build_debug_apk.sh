#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if ! command -v gradle >/dev/null 2>&1; then
  echo 'Gradle 8.10.2 is required. Install it, or run the included GitHub Actions workflow.' >&2
  exit 2
fi
if [[ -z "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]]; then
  echo 'Set ANDROID_HOME to an Android SDK containing Android 35 and Build Tools 35.0.0.' >&2
  exit 2
fi
gradle --no-daemon --stacktrace :app:assembleDebug
out='app/build/outputs/apk/debug/app-debug.apk'
test -s "$out"
cp "$out" IRAQ_TRANSLATE_1.2.0-debug.apk
sha256sum IRAQ_TRANSLATE_1.2.0-debug.apk > IRAQ_TRANSLATE_1.2.0-debug.apk.sha256
echo "Debug APK created at $PWD/IRAQ_TRANSLATE_1.2.0-debug.apk"
