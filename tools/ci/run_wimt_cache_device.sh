#!/usr/bin/env bash
# Install the APK from this checkout and exercise the selected `.in` web-cache source once.
# A live refusal/empty answer remains a failure; screenshots are captured inside the app.
set -euo pipefail
OUT=${WIMT_CACHE_EVIDENCE_DIR:-wimt-cache-device-evidence}
mkdir -p "$OUT/screens"
: > "$OUT/instrumentation.txt"
finish() {
  rc=$?
  set +e
  export PATH="${ANDROID_HOME:-/usr/local/lib/android/sdk}/platform-tools:$PATH"
  for name in wimt-cache-before-selected-source wimt-cache-after-selected-source \
              wimt-cache-source-details wimt-cache-final-even-on-failure; do
    for ext in png xml; do
      adb exec-out run-as in.opentrack.india cat "files/evidence/$name.$ext" \
        > "$OUT/screens/$name.$ext" 2>/dev/null || rm -f "$OUT/screens/$name.$ext"
    done
  done
  adb logcat -d -s WimtCacheLiveAcceptance:I > "$OUT/device-log.txt" 2>/dev/null || true
  cat "$OUT/device-log.txt" >> "$OUT/instrumentation.txt" 2>/dev/null || true
  accepted=()
  if grep -q 'OK (1 test)' "$OUT/instrumentation.txt" && \
     grep -q 'WIMT_CACHE_DEVICE_VERDICT=PASS_DATED_CACHE_ROWS_SHOWN_AS_UNVERIFIED_COMPARISON' \
       "$OUT/instrumentation.txt" && ! grep -q 'FAILURES!!!' "$OUT/instrumentation.txt"; then
    accepted=(--accepted)
  fi
  if ! python3 tools/ci/verify_wimt_cache_screens.py "$OUT" "${accepted[@]}" \
       > "$OUT/screen-verification.json" 2> "$OUT/screen-verification-error.txt"; then
    rm -f "$OUT/screen-verification.json"
    cat "$OUT/screen-verification-error.txt"
    # Preserve the test's original source refusal when screenshots could not be
    # verified; otherwise make a missing app capture itself a failing device run.
    [ "$rc" -ne 0 ] || rc=4
  fi
  python3 tools/ci/wimt_cache_device_report.py "$OUT/instrumentation.txt" "$rc" \
    "$OUT/screen-verification.json" > "$OUT/result.json" || true
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
adb pull "$installed" /tmp/wimt-cache-installed.apk >/dev/null
adb pull "$installed_test" /tmp/wimt-cache-installed-test.apk >/dev/null
{
  printf 'built app %s\n' "$(sha256sum app/build/outputs/apk/debug/app-debug.apk)"
  printf 'installed app %s\n' "$(sha256sum /tmp/wimt-cache-installed.apk)"
  printf 'built instrumentation %s\n' "$(sha256sum app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk)"
  printf 'installed instrumentation %s\n' "$(sha256sum /tmp/wimt-cache-installed-test.apk)"
} | tee "$OUT/apk-install-proof.txt"
cmp app/build/outputs/apk/debug/app-debug.apk /tmp/wimt-cache-installed.apk
cmp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk /tmp/wimt-cache-installed-test.apk
rm -f /tmp/wimt-cache-installed.apk /tmp/wimt-cache-installed-test.apk
adb shell pm clear in.opentrack.india
adb logcat -c
# A nonzero status OR a missing exact test summary is red, independent of the
# shell exit convention of `am instrument`.
set +e
timeout 240 adb shell am instrument -w -e class \
  in.opentrack.india.e2e.WimtCacheLiveAcceptanceTest#installedApkMustPaintDatedCacheRowsAsAnUnverifiedComparison \
  in.opentrack.india.test/androidx.test.runner.AndroidJUnitRunner \
  > "$OUT/instrumentation.txt" 2>&1
rc=$?
set -e
if [ "$rc" -ne 0 ] || ! grep -q 'OK (1 test)' "$OUT/instrumentation.txt" || \
   grep -q 'FAILURES!!!' "$OUT/instrumentation.txt"; then
  tail -45 "$OUT/instrumentation.txt"
  exit 1
fi
# EXIT trap verifies the in-app captures and constructs the redacted source verdict.
