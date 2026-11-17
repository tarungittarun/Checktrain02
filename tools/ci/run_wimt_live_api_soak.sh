#!/usr/bin/env bash
# Install the exact APKs. Existing modes require fresh WIMT .in data; the 30-minute
# diagnostic mode also continues passive 20-second UI observation after a source failure.
set -euo pipefail
OUT=${WIMT_LIVE_SOAK_EVIDENCE_DIR:-wimt-live-api-soak-evidence}
SOAK_MINUTES=${WIMT_LIVE_SOAK_MINUTES:-240}
SOAK_TRAIN_NO=${WIMT_LIVE_SOAK_TRAIN_NO:-}
OBSERVATION_INTERVAL_SECONDS=${WIMT_LIVE_SOAK_OBSERVATION_INTERVAL_SECONDS:-0}
case "$SOAK_MINUTES" in
  30)
    TEST_METHOD=selectedWimtAppRemainsObservableEvery20SecondsFor30Minutes
    TEST_TIMEOUT_SECONDS=2700
    if [[ ! "$SOAK_TRAIN_NO" =~ ^[1-9][0-9]{3,4}$ ]]; then
      echo "30-minute installed-app observation requires WIMT_LIVE_SOAK_TRAIN_NO (4-5 digits)" >&2
      exit 2
    fi
    if [ "$OBSERVATION_INTERVAL_SECONDS" != 20 ]; then
      echo "30-minute installed-app observation requires a 20-second sample interval" >&2
      exit 2
    fi
    ;;
  46)
    TEST_METHOD=selectedWimtAutoRefreshRemainsStableFor46Minutes
    TEST_TIMEOUT_SECONDS=3600
    ;;
  240)
    TEST_METHOD=selectedWimtAutoRefreshRemainsStableFor240Minutes
    TEST_TIMEOUT_SECONDS=17400
    ;;
  *)
    echo "Unsupported opt-in WIMT soak duration: $SOAK_MINUTES (allowed: 30, 46, 240)" >&2
    exit 2
    ;;
esac
mkdir -p "$OUT/screens"
: > "$OUT/instrumentation.txt"
: > "$OUT/device-log.txt"

finish() {
  rc=$?
  set +e
  export PATH="${ANDROID_HOME:-/usr/local/lib/android/sdk}/platform-tools:$PATH"
  for name in \
      wimt-soak-before wimt-soak-start wimt-soak-start-details \
      wimt-soak-5m wimt-soak-5m-details wimt-soak-10m wimt-soak-10m-details \
      wimt-soak-15m wimt-soak-15m-details \
      wimt-soak-20m wimt-soak-20m-details wimt-soak-25m wimt-soak-25m-details \
      wimt-soak-30m wimt-soak-30m-details \
      wimt-soak-45m wimt-soak-45m-details \
      wimt-soak-46m wimt-soak-46m-details \
      wimt-soak-60m wimt-soak-60m-details \
      wimt-soak-120m wimt-soak-120m-details \
      wimt-soak-180m wimt-soak-180m-details \
      wimt-soak-240m wimt-soak-240m-details \
      wimt-soak-auto-enable-failed wimt-soak-final-even-on-failure; do
    for extension in png xml; do
      path="files/evidence/$name.$extension"
      destination="$OUT/screens/$name.$extension"
      adb exec-out run-as in.opentrack.india cat "$path" \
        > "$destination" 2>/dev/null || rm -f "$destination"
    done
  done
  adb exec-out run-as in.opentrack.india cat files/evidence/wimt-soak-results.txt \
    > "$OUT/soak-trace.txt" 2>/dev/null || : > "$OUT/soak-trace.txt"
  adb logcat -d -s WimtLiveApiSoak:I > "$OUT/device-log.txt" 2>/dev/null || true
  if ! grep -q 'OK (1 test)' "$OUT/instrumentation.txt" || \
      grep -q 'FAILURES!!!' "$OUT/instrumentation.txt" || \
      grep -q 'INSTRUMENTATION_FAILED' "$OUT/instrumentation.txt"; then
    [ "$rc" -ne 0 ] || rc=1
  fi
  printf 'instrumentationExitCode=%s\n' "$rc" > "$OUT/runner-exit.txt"
  trap - EXIT
  exit "$rc"
}
trap finish EXIT

export API=34 IMG=google_apis ABI=x86_64 DEVICE=pixel_6 BOOT_TIMEOUT=600
bash tools/ci/emulator_boot.sh > "$OUT/boot.txt" 2>&1 || {
  tail -35 "$OUT/boot.txt"
  exit 3
}
export PATH="${ANDROID_HOME:-/usr/local/lib/android/sdk}/platform-tools:$PATH"
adb shell svc power stayon true >/dev/null 2>&1 || true
adb shell settings put global stay_on_while_plugged_in 3 >/dev/null 2>&1 || true
adb shell settings put system screen_off_timeout 2147483647 >/dev/null 2>&1 || true
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
installed=$(adb shell pm path in.opentrack.india | tr -d '\r' | sed -n 's/^package://p' | head -1)
installed_test=$(adb shell pm path in.opentrack.india.test | tr -d '\r' | sed -n 's/^package://p' | head -1)
[ -n "$installed" ] || { echo "PackageManager cannot find installed app APK"; exit 1; }
[ -n "$installed_test" ] || { echo "PackageManager cannot find installed instrumentation APK"; exit 1; }
adb pull "$installed" /tmp/wimt-soak-installed.apk >/dev/null
adb pull "$installed_test" /tmp/wimt-soak-installed-test.apk >/dev/null
{
  printf 'built app %s\n' "$(sha256sum app/build/outputs/apk/debug/app-debug.apk)"
  printf 'installed app %s\n' "$(sha256sum /tmp/wimt-soak-installed.apk)"
  printf 'built instrumentation %s\n' "$(sha256sum app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk)"
  printf 'installed instrumentation %s\n' "$(sha256sum /tmp/wimt-soak-installed-test.apk)"
} | tee "$OUT/apk-install-proof.txt"
cmp app/build/outputs/apk/debug/app-debug.apk /tmp/wimt-soak-installed.apk
cmp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk /tmp/wimt-soak-installed-test.apk
rm -f /tmp/wimt-soak-installed.apk /tmp/wimt-soak-installed-test.apk
adb shell pm clear in.opentrack.india
adb logcat -c
# Existing 46/240-minute modes require fresh WIMT .in rows before Auto. The 30-minute
# diagnostic records an unqualified initial response, keeps Auto OFF, and continues only passive
# UI sampling; a qualified response uses the app's normal one-minute Auto cadence. Refusals stop polling.
set +e
timeout --signal=TERM --kill-after=60 "${TEST_TIMEOUT_SECONDS}s" adb shell am instrument -w \
  -e class "in.opentrack.india.e2e.WimtLiveApiSoakTest#$TEST_METHOD" \
  -e soak_minutes "$SOAK_MINUTES" \
  -e soak_train_no "$SOAK_TRAIN_NO" \
  -e observation_interval_seconds "$OBSERVATION_INTERVAL_SECONDS" \
  in.opentrack.india.test/androidx.test.runner.AndroidJUnitRunner \
  > "$OUT/instrumentation.txt" 2>&1
rc=$?
set -e
if [ "$rc" -ne 0 ] || ! grep -q 'OK (1 test)' "$OUT/instrumentation.txt" || \
   grep -q 'FAILURES!!!' "$OUT/instrumentation.txt"; then
  tail -70 "$OUT/instrumentation.txt"
  exit 1
fi
