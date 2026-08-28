#!/usr/bin/env bash
set -euo pipefail

APP_ID="com.goodlight.floatingvoicebubble"
ACTIVITY="$APP_ID/.MainActivity"
ACCESSIBILITY="$APP_ID/.accessibility.VoiceBubbleAccessibilityService"
DEBUG_APK="app/build/outputs/apk/debug/app-debug.apk"
RELEASE_DIR="app/build/outputs/apk/release"
GRADLE_CMD="${GRADLE_CMD:-gradle}"
API_LEVEL="unknown"
REPORT_ROOT="app/build/reports/androidTests/emulator-diagnostics"
EVIDENCE_DIR="app/build/reports/androidTests/ui-evidence"
LOG_FILE="$REPORT_ROOT/emulator-verification.log"
BUILD_TOOLS="$ANDROID_HOME/build-tools/36.0.0"
TEMP_ROOT="${RUNNER_TEMP:-/tmp}"
R8_ALIGNED_APK="$TEMP_ROOT/fvb-r8-release-aligned.apk"
R8_TEST_APK="$TEMP_ROOT/fvb-r8-release-test-signed.apk"
R8_TEST_KEYSTORE="$TEMP_ROOT/fvb-r8-runtime-test.jks"
R8_TEST_ALIAS="fvb-runtime-test"
R8_TEST_PASSWORD="fvb-runtime-test"

mkdir -p "$REPORT_ROOT" "$EVIDENCE_DIR"
: > "$LOG_FILE"
exec > >(tee -a "$LOG_FILE") 2>&1

preserve_reports() {
  set +e
  mkdir -p "$REPORT_ROOT"
  cp -a app/build/outputs/androidTest-results "$REPORT_ROOT/" 2>/dev/null || true
  adb logcat -d > "$REPORT_ROOT/logcat-api${API_LEVEL}.txt" 2>/dev/null || true
  adb shell dumpsys accessibility > "$REPORT_ROOT/accessibility-api${API_LEVEL}.txt" 2>/dev/null || true
}
trap preserve_reports EXIT

stage() {
  printf '\n===== %s =====\n' "$1"
}

launch_main() {
  local output
  output="$(adb shell am start -W -n "$ACTIVITY")"
  printf '%s\n' "$output"
  printf '%s\n' "$output" | grep -q "Status: ok"
  local pid
  pid="$(adb shell pidof "$APP_ID" | tr -d '\r')"
  test -n "$pid"
  echo "PID=$pid"
}

bind_accessibility() {
  adb shell settings put secure enabled_accessibility_services "$ACCESSIBILITY"
  adb shell settings put secure accessibility_enabled 1
  sleep 2
  adb shell dumpsys accessibility | tee "$REPORT_ROOT/accessibility-bound-api${API_LEVEL}.txt" | grep -F "$APP_ID" >/dev/null
}

capture_ui() {
  local stem="$1"
  local remote_xml="/sdcard/${stem}.xml"
  local screenshot="$EVIDENCE_DIR/${stem}.png"
  local ui_xml="$EVIDENCE_DIR/${stem}.xml"
  adb exec-out screencap -p > "$screenshot"
  test -s "$screenshot"
  adb shell uiautomator dump "$remote_xml" >/dev/null
  adb pull "$remote_xml" "$ui_xml" >/dev/null
  test -s "$ui_xml"
}

verify_home_contract() {
  local ui_xml="$1"
  python3 - "$ui_xml" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

path = sys.argv[1]
root = ET.parse(path).getroot()

required = {
    "文章補正": False,
    "推論の深さ": False,
    "聞き取り間違いを直す強さ": False,
    "音声認識": False,
}
forbidden = ["安全ガード", "フィラー", "簡単設定", "ASR"]

reasoning_bounds = []
all_bounds = []
all_text = []
for node in root.iter():
    text = (node.attrib.get("text") or "") + " " + (node.attrib.get("content-desc") or "")
    all_text.append(text)
    for label in required:
        if label in text:
            required[label] = True
    bounds = node.attrib.get("bounds", "")
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
    if match:
        parsed = tuple(map(int, match.groups()))
        all_bounds.append(parsed)
        if "推論の深さ" in text:
            reasoning_bounds.append(parsed)

missing = [label for label, found in required.items() if not found]
if missing:
    raise SystemExit(f"launcher UI missing required visible controls: {missing}")
joined = "\n".join(all_text)
seen_forbidden = [label for label in forbidden if label in joined]
if seen_forbidden:
    raise SystemExit(f"launcher UI contains removed/ambiguous language: {seen_forbidden}")
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
}

stage "build debug + androidTest + R8 release"
$GRADLE_CMD --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease

test -s "$DEBUG_APK"
unzip -t "$DEBUG_APK" >/dev/null
mapfile -t RELEASE_APKS < <(find "$RELEASE_DIR" -maxdepth 1 -type f -name '*.apk' | sort)
test "${#RELEASE_APKS[@]}" -eq 1
RELEASE_APK="${RELEASE_APKS[0]}"
test -s "$RELEASE_APK"
unzip -t "$RELEASE_APK" >/dev/null
sha256sum "$RELEASE_APK" | tee "$REPORT_ROOT/r8-release-unsigned-sha256.txt"

stage "align and test-sign R8 release with ephemeral CI identity"
rm -f "$R8_ALIGNED_APK" "$R8_TEST_APK" "$R8_TEST_KEYSTORE"
"$BUILD_TOOLS/zipalign" -P 16 -f -v 4 "$RELEASE_APK" "$R8_ALIGNED_APK" > "$REPORT_ROOT/r8-release-pre-sign-zipalign.txt"
"$BUILD_TOOLS/zipalign" -c -P 16 -v 4 "$R8_ALIGNED_APK" >> "$REPORT_ROOT/r8-release-pre-sign-zipalign.txt"
keytool -genkeypair \
  -keystore "$R8_TEST_KEYSTORE" \
  -storepass "$R8_TEST_PASSWORD" \
  -keypass "$R8_TEST_PASSWORD" \
  -alias "$R8_TEST_ALIAS" \
  -keyalg RSA \
  -keysize 2048 \
  -validity 2 \
  -dname "CN=Floating VoiceBubble CI Runtime Test,O=GoodLight999,C=JP" \
  -noprompt >/dev/null 2>&1
chmod 600 "$R8_TEST_KEYSTORE"
"$BUILD_TOOLS/apksigner" sign \
  --ks "$R8_TEST_KEYSTORE" \
  --ks-key-alias "$R8_TEST_ALIAS" \
  --ks-pass "pass:$R8_TEST_PASSWORD" \
  --key-pass "pass:$R8_TEST_PASSWORD" \
  --out "$R8_TEST_APK" \
  "$R8_ALIGNED_APK"
"$BUILD_TOOLS/apksigner" verify --verbose --print-certs "$R8_TEST_APK" | tee "$REPORT_ROOT/r8-release-test-signature.txt"
"$BUILD_TOOLS/zipalign" -c -P 16 -v 4 "$R8_TEST_APK" > "$REPORT_ROOT/r8-release-test-zipalign.txt"
sha256sum "$R8_TEST_APK" | tee "$REPORT_ROOT/r8-release-test-signed-sha256.txt"

stage "boot"
adb wait-for-device
API_LEVEL="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
echo "API_LEVEL=$API_LEVEL"

stage "install and launch R8 release"
adb install -r "$R8_TEST_APK" >/dev/null
adb shell pm grant "$APP_ID" android.permission.RECORD_AUDIO || true
adb shell cmd uimode night no >/dev/null 2>&1 || true
adb shell am force-stop "$APP_ID" || true
launch_main
bind_accessibility
capture_ui "release-home-light-api${API_LEVEL}"
verify_home_contract "$EVIDENCE_DIR/release-home-light-api${API_LEVEL}.xml"

stage "capture R8 release dark mode"
adb shell cmd uimode night yes >/dev/null 2>&1 || true
adb shell am force-stop "$APP_ID" || true
launch_main
sleep 1
capture_ui "release-home-dark-api${API_LEVEL}"
verify_home_contract "$EVIDENCE_DIR/release-home-dark-api${API_LEVEL}.xml"

stage "install debug for instrumentation"
adb shell cmd uimode night no >/dev/null 2>&1 || true
adb shell am force-stop "$APP_ID" || true
adb uninstall "$APP_ID" >/dev/null 2>&1 || true
adb install -t "$DEBUG_APK" >/dev/null
adb shell pm grant "$APP_ID" android.permission.RECORD_AUDIO || true
launch_main
bind_accessibility
capture_ui "home-api${API_LEVEL}"
verify_home_contract "$EVIDENCE_DIR/home-api${API_LEVEL}.xml"

stage "connected instrumentation"
set +e
$GRADLE_CMD --no-daemon :app:connectedDebugAndroidTest
TEST_STATUS=$?
set -e

preserve_reports
if [[ $TEST_STATUS -ne 0 ]]; then
  echo "connectedDebugAndroidTest FAILED with status $TEST_STATUS"
  find app/build -type f \( -name '*.xml' -o -name '*.html' \) | sort | tail -n 100 || true
  exit "$TEST_STATUS"
fi

stage "force-stop"
adb shell am force-stop "$APP_ID" || true
echo "emulator verification PASS"
