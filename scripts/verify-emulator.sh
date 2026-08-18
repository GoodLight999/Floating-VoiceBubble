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

# Capture and verify the actual launcher UI before any test scrolls it.
# The reasoning control must be visible on first paint; merely existing lower in
# a scroll container is not sufficient.
API_LEVEL="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
EVIDENCE_TMP="${RUNNER_TEMP:-/tmp}/fvb-ui-evidence-api${API_LEVEL}"
EVIDENCE_DIR="app/build/reports/androidTests/ui-evidence"
mkdir -p "$EVIDENCE_TMP"
SCREENSHOT="$EVIDENCE_TMP/home-api${API_LEVEL}.png"
UI_XML="$EVIDENCE_TMP/home-api${API_LEVEL}.xml"

preserve_ui_evidence() {
  mkdir -p "$EVIDENCE_DIR"
  cp -f "$SCREENSHOT" "$UI_XML" "$EVIDENCE_DIR/" 2>/dev/null || true
}
trap preserve_ui_evidence EXIT

adb exec-out screencap -p > "$SCREENSHOT"
test -s "$SCREENSHOT"
adb shell uiautomator dump /sdcard/fvb-home.xml >/dev/null
adb pull /sdcard/fvb-home.xml "$UI_XML" >/dev/null
test -s "$UI_XML"

python3 - "$UI_XML" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

path = sys.argv[1]
root = ET.parse(path).getroot()

required = {
    "AI補正": False,
    "シンキング": False,
    "聞き取りミス修復": False,
    "音声認識": False,
}

reasoning_bounds = []
all_bounds = []
for node in root.iter():
    text = (node.attrib.get("text") or "") + " " + (node.attrib.get("content-desc") or "")
    for label in required:
        if label in text:
            required[label] = True
    bounds = node.attrib.get("bounds", "")
    m = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
    if m:
        parsed = tuple(map(int, m.groups()))
        all_bounds.append(parsed)
        if "シンキング" in text:
            reasoning_bounds.append(parsed)

missing = [label for label, found in required.items() if not found]
if missing:
    raise SystemExit(f"launcher UI missing required visible controls: {missing}")
if not reasoning_bounds:
    raise SystemExit("reasoning control has no visible bounds in launcher UI")
if not all_bounds:
    raise SystemExit("launcher UI dump contains no bounds")

height = max(bounds[3] for bounds in all_bounds)
reasoning_top = min(bounds[1] for bounds in reasoning_bounds)
if reasoning_top >= height * 0.50:
    raise SystemExit(
        f"reasoning control is buried too low on first paint: y={reasoning_top}, screenHeight={height}"
    )
print(f"launcher UI contract PASS: reasoning y={reasoning_top}, screenHeight={height}")
PY

preserve_ui_evidence
$GRADLE_CMD --no-daemon :app:connectedDebugAndroidTest

adb shell am force-stop "$APP_ID" || true
