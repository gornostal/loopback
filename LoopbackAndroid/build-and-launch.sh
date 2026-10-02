#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/platform-tools:$PATH"

PACKAGE=name.gornostal.loopback
APK=app/build/outputs/apk/debug/app-debug.apk

echo "==> Building"
./gradlew assembleDebug -x lint

# adb refuses to pick a target when several devices are attached; ANDROID_SERIAL
# pins every adb call below to one. Respect a caller-provided value.
if [[ -z "${ANDROID_SERIAL:-}" ]]; then
  ANDROID_SERIAL="$(adb devices | awk 'NR > 1 && $2 == "device" { print $1; exit }')"
  [[ -n "$ANDROID_SERIAL" ]] || { echo "No adb device available" >&2; exit 1; }
  export ANDROID_SERIAL
fi

echo "==> Installing on $ANDROID_SERIAL"
# A signature mismatch (e.g. a build from another machine) fails `install -r`;
# fall back to a clean install.
adb install -r "$APK" | grep -q Success || {
  adb uninstall "$PACKAGE" >/dev/null 2>&1 || true
  adb install "$APK" | grep -q Success
}

echo "==> Launching"
adb shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1

echo "==> Done"
