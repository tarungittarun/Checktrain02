#!/usr/bin/env bash
# Install the exact APKs, require fresh WIMT .in clocks in the UI, then run the opt-in
# selected-source one-minute refresh regression for exactly 240 minutes.
set -euo pipefail
OUT=${WIMT_LIVE_SOAK_EVIDENCE_DIR:-wimt-live-api-soak-evidence}
mkdir -p "$OUT/screens"
: > "$OUT/instrumentation.txt"
: > "$OUT/device-log.txt"

finish() {
  rc=$?
  set +e
  export PATH="${ANDROID_HOME:-/usr/local/lib/android/sdk}/platform-tools:$PATH"
  for name in \
      wimt-soak-before wimt-soak-start wimt-soak-start-details \
      wimt-soak-60m wimt-soak-60m-details \
      wimt-soak-120m wimt-soak-120m-details \
      wimt-soak-180m wimt-soak-180m-details \
      wimt-soak-240m wimt-soak-240m-details \
      wimt-soak-auto-enable-failed wimt-soak-final-even-on-failure; do
    adb exec-out run-as in.opentrack.india cat "files/evidence/$name.png" \
      > "$OUT/screens/$name.png" 2>/dev/null || rm -f "$OUT/screens/$name.png"
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
# The instrumented test itself requires fresh, run/date/route-matched WIMT .in clocks
# before it enables Auto. A refusal stops subsequent source polling rather than retrying it.
set +e
timeout --signal=TERM --kill-after=60 17400s adb shell am instrument -w \
  -e class in.opentrack.india.e2e.WimtLiveApiSoakTest#selectedWimtAutoRefreshRemainsStableFor240Minutes \
  -e soak_minutes 240 \
  in.opentrack.india.test/androidx.test.runner.AndroidJUnitRunner \
  > "$OUT/instrumentation.txt" 2>&1
rc=$?
set -e
if [ "$rc" -ne 0 ] || ! grep -q 'OK (1 test)' "$OUT/instrumentation.txt" || \
   grep -q 'FAILURES!!!' "$OUT/instrumentation.txt"; then
  tail -70 "$OUT/instrumentation.txt"
  exit 1
fi
