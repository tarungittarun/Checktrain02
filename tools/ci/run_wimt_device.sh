#!/usr/bin/env bash
# Install the exact locally built APK and run ONE online, source-strict UI journey.
# Always retain screenshots and a bounded result, even after an emulator/test failure.
set -euo pipefail
mkdir -p wimt-device-evidence/screens
finish() {
  rc=$?
  set +e
  adb exec-out screencap -p > wimt-device-evidence/final.png 2>/dev/null || true
  adb shell uiautomator dump /sdcard/wimt-window.xml >/dev/null 2>&1 || true
  adb pull /sdcard/wimt-window.xml wimt-device-evidence/window.xml >/dev/null 2>&1 || true
  adb pull /sdcard/Android/data/in.opentrack.india.test/files/evidence \
    wimt-device-evidence/screens >/dev/null 2>&1 || true
  # Raw device diary is transient: never upload it or post CDN IDs, cookies or IPs.
  adb shell run-as in.opentrack.india cat files/diag/log.txt \
    > /tmp/wimt-device-private-diary.txt 2>/dev/null || true
  python3 tools/ci/wimt_device_report.py wimt-device-evidence/instrumentation.txt \
    /tmp/wimt-device-private-diary.txt "$rc" > wimt-device-evidence/result.json || true
  rm -f /tmp/wimt-device-private-diary.txt
  cat wimt-device-evidence/result.json
}
trap finish EXIT
export API=34 IMG=google_apis ABI=x86_64 DEVICE=pixel_6 BOOT_TIMEOUT=360
bash tools/ci/emulator_boot.sh > wimt-device-evidence/boot.txt 2>&1 || {
  tail -20 wimt-device-evidence/boot.txt
  exit 3
}
export PATH="${ANDROID_HOME:-/usr/local/lib/android/sdk}/platform-tools:$PATH"
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm clear in.opentrack.india
# Android's instrumentation command can return zero even when tests fail; the
# report gate independently requires exactly one successful instrumented method.
set +e
timeout 180 adb shell am instrument -w -e class \
  in.opentrack.india.e2e.WimtWebsiteLiveAcceptanceTest#websiteRefreshMustPaintARealSourceQualifiedPastCard \
  in.opentrack.india.test/androidx.test.runner.AndroidJUnitRunner \
  > wimt-device-evidence/instrumentation.txt 2>&1
rc=$?
set -e
if [ "$rc" -ne 0 ] || ! grep -q 'OK (1 test)' wimt-device-evidence/instrumentation.txt; then
  tail -35 wimt-device-evidence/instrumentation.txt
  exit 1
fi
# The EXIT trap writes the redacted result. We still require the independent
# source verdict here, so a green UI test alone cannot claim a live WIMT answer.
adb shell run-as in.opentrack.india cat files/diag/log.txt > /tmp/wimt-device-private-diary.txt 2>/dev/null || true
python3 tools/ci/wimt_device_report.py wimt-device-evidence/instrumentation.txt \
  /tmp/wimt-device-private-diary.txt 0 > wimt-device-evidence/result.json
rm -f /tmp/wimt-device-private-diary.txt
