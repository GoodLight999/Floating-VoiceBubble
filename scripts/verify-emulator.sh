#!/usr/bin/env bash
set -euo pipefail

APP_ID="com.goodlight.floatingvoicebubble"
ACTIVITY="$APP_ID/.MainActivity"
ACCESSIBILITY="$APP_ID/.accessibility.VoiceBubbleAccessibilityService"
APK="app/build/outputs/apk/debug/app-debug.apk"

GRADLE_CMD="${GRADLE_CMD:-gradle}"

$GRADLE_CMD --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest

test -s "$APK"
unzip -t "$APK" >/dev/null

adb wait-for-device
adb install -r -t "$APK" >/dev/null
adb shell pm grant "$APP_ID" android.permission.RECORD_AUDIO || true

START_OUTPUT="$(adb shell am start -W -n "$ACTIVITY")"
printf '%s\n' "$START_OUTPUT"
printf '%s\n' "$START_OUTPUT" | grep -q "Status: ok"

PID="$(adb shell pidof "$APP_ID" | tr -d '\r')"
test -n "$PID"

# Enable the accessibility service on the disposable emulator and confirm that
# Android actually binds it. This exercises service creation + overlay attach.
adb shell settings put secure enabled_accessibility_services "$ACCESSIBILITY"
adb shell settings put secure accessibility_enabled 1
sleep 2
adb shell dumpsys accessibility | grep -F "$APP_ID" >/dev/null

$GRADLE_CMD --no-daemon :app:connectedDebugAndroidTest

adb shell am force-stop "$APP_ID" || true
