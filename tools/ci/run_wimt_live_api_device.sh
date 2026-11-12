#!/usr/bin/env bash
# Install the exact debug APK, select WIMT .in once, and retain only its own in-app evidence.
set -euo pipefail
OUT=${WIMT_LIVE_EVIDENCE_DIR:-wimt-live-device-evidence}
mkdir -p "$OUT/screens"
: > "$OUT/instrumentation.txt"
finish() {
  rc=$?
  set +e
  export PATH="${ANDROID_HOME:-/usr/local/lib/android/sdk}/platform-tools:$PATH"
  # Pull only screenshots/hierarchies captured by E2E.shotWimt inside the running target app.
  for name in wimt-live-before-selected-source wimt-live-after-selected-source \
              wimt-live-source-details wimt-live-final-even-on-failure; do
    for ext in png xml; do
      adb exec-out run-as in.opentrack.india cat "files/evidence/$name.$ext" \
        > "$OUT/screens/$name.$ext" 2>/dev/null || rm -f "$OUT/screens/$name.$ext"
    done
  done
  adb logcat -d -s WimtLiveApiAcceptance:I > "$OUT/device-log.txt" 2>/dev/null || true
  cat "$OUT/device-log.txt" >> "$OUT/instrumentation.txt" 2>/dev/null || true
  screen_mode=()
  if grep -q 'OK (1 test)' "$OUT/instrumentation.txt" && \
     grep -q 'WIMT_LIVE_DEVICE_VERDICT=PASS_FRESH_MATCHED_SOURCE_TIMES_IN_APP' \
       "$OUT/instrumentation.txt" && ! grep -q 'FAILURES!!!' "$OUT/instrumentation.txt"; then
    screen_mode=(--accepted)
  fi
  if ! python3 tools/ci/verify_wimt_live_screens.py "$OUT" "${screen_mode[@]}" \
      > "$OUT/screen-verification.json" 2> "$OUT/screen-verification-error.txt"; then
    rm -f "$OUT/screen-verification.json"
    cat "$OUT/screen-verification-error.txt"
    [ "$rc" -ne 0 ] || rc=4
  fi
  python3 tools/ci/wimt_live_device_report.py "$OUT/instrumentation.txt" "$rc" \
    "$OUT/screen-verification.json" > "$OUT/result.json" || true
  if [ "$rc" -eq 0 ] && ! python3 - "$OUT/result.json" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as f:
    raise SystemExit(0 if json.load(f).get("accepted") is True else 1)
PY
  then
    rc=5
    python3 tools/ci/wimt_live_device_report.py "$OUT/instrumentation.txt" "$rc" \
      "$OUT/screen-verification.json" > "$OUT/result.json" || true
  fi
  cat "$OUT/result.json" 2>/dev/null || true
  trap - EXIT
  exit "$rc"
}
trap finish EXIT

export API=34 IMG=google_apis ABI=x86_64 DEVICE=pixel_6 BOOT_TIMEOUT=360
bash tools/ci/emulator_boot.sh > "$OUT/boot.txt" 2>&1 || {
  tail -25 "$OUT/boot.txt"
  exit 3
}
export PATH="${ANDROID_HOME:-/usr/local/lib/android/sdk}/platform-tools:$PATH"
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
installed=$(adb shell pm path in.opentrack.india | tr -d '\r' | sed -n 's/^package://p' | head -1)
installed_test=$(adb shell pm path in.opentrack.india.test | tr -d '\r' | sed -n 's/^package://p' | head -1)
[ -n "$installed" ] || { echo "PackageManager cannot find installed app APK"; exit 1; }
[ -n "$installed_test" ] || { echo "PackageManager cannot find installed instrumentation APK"; exit 1; }
adb pull "$installed" /tmp/wimt-live-installed.apk >/dev/null
adb pull "$installed_test" /tmp/wimt-live-installed-test.apk >/dev/null
{
  printf 'built app %s\n' "$(sha256sum app/build/outputs/apk/debug/app-debug.apk)"
  printf 'installed app %s\n' "$(sha256sum /tmp/wimt-live-installed.apk)"
  printf 'built instrumentation %s\n' "$(sha256sum app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk)"
  printf 'installed instrumentation %s\n' "$(sha256sum /tmp/wimt-live-installed-test.apk)"
} | tee "$OUT/apk-install-proof.txt"
cmp app/build/outputs/apk/debug/app-debug.apk /tmp/wimt-live-installed.apk
cmp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk /tmp/wimt-live-installed-test.apk
rm -f /tmp/wimt-live-installed.apk /tmp/wimt-live-installed-test.apk
adb shell pm clear in.opentrack.india
adb logcat -c
# One deliberate `.in` source selection; the test never retries it or enables Auto refresh.
# The app's normal first-load/identity behavior remains unchanged.
set +e
timeout 240 adb shell am instrument -w -e class \
  in.opentrack.india.e2e.WimtLiveApiAcceptanceTest#installedApkMustFetchFreshRunMatchedWimtLiveStopTimes \
  in.opentrack.india.test/androidx.test.runner.AndroidJUnitRunner \
  > "$OUT/instrumentation.txt" 2>&1
rc=$?
set -e
if [ "$rc" -ne 0 ] || ! grep -q 'OK (1 test)' "$OUT/instrumentation.txt" || \
   grep -q 'FAILURES!!!' "$OUT/instrumentation.txt"; then
  tail -50 "$OUT/instrumentation.txt"
  exit 1
fi
# EXIT trap requires matching fresh source evidence and all three in-app captures before a pass.
