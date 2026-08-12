#!/usr/bin/env bash
set -euo pipefail

GRADLE_CMD="${GRADLE_CMD:-gradle}"
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-36.0.0}"
APK="app/build/outputs/apk/debug/app-debug.apk"

if [[ "${VOICEBUBBLE_SKIP_REMOTE_PROBES:-0}" != "1" ]]; then
  bash scripts/verify-model-catalog.sh
fi

$GRADLE_CMD --no-daemon :app:lintDebug :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease

test -s "$APK"
unzip -t "$APK" >/dev/null
unzip -l "$APK" > /tmp/floating-voicebubble-apk-contents.txt
grep -F "lib/x86_64/libsherpa-onnx-jni.so" /tmp/floating-voicebubble-apk-contents.txt
grep -F "lib/arm64-v8a/libsherpa-onnx-jni.so" /tmp/floating-voicebubble-apk-contents.txt

if [[ -n "$SDK_ROOT" && -x "$SDK_ROOT/build-tools/$BUILD_TOOLS_VERSION/zipalign" ]]; then
  "$SDK_ROOT/build-tools/$BUILD_TOOLS_VERSION/zipalign" -c -P 16 -v 4 "$APK"
fi
if [[ -n "$SDK_ROOT" && -x "$SDK_ROOT/build-tools/$BUILD_TOOLS_VERSION/apksigner" ]]; then
  "$SDK_ROOT/build-tools/$BUILD_TOOLS_VERSION/apksigner" verify --verbose --print-certs "$APK"
fi
if [[ -n "$SDK_ROOT" && -x "$SDK_ROOT/build-tools/$BUILD_TOOLS_VERSION/aapt" ]]; then
  "$SDK_ROOT/build-tools/$BUILD_TOOLS_VERSION/aapt" dump badging "$APK" | grep -E "^(package:|sdkVersion:|targetSdkVersion:|launchable-activity:)"
fi

if command -v adb >/dev/null 2>&1 && adb get-state >/dev/null 2>&1; then
  bash scripts/verify-emulator.sh
else
  echo "No Android device/emulator attached: connected runtime checks skipped."
fi
