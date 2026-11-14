#!/usr/bin/env bash
# Install the exact debug APK and compare a small set of live WIMT runs in the installed app.
# Each source request is deliberate, read-only and single-shot; a refusal is not retried.
set -euo pipefail
OUT=${WIMT_LIVE_EVIDENCE_DIR:-wimt-live-device-evidence}
mkdir -p "$OUT/screens"
: > "$OUT/instrumentation.txt"
: > "$OUT/device-log.txt"
finish() {
  rc=$?
  set +e
  export PATH="${ANDROID_HOME:-/usr/local/lib/android/sdk}/platform-tools:$PATH"
  # Recover only captures named by the fresh test. The last run reported all expected PNG/XML
  # files missing after recovery; list with direct exec-out run-as and retain counts/errors.
  capture_listing="$OUT/capture-listing.txt"
  capture_listing_error="$OUT/capture-access-error.txt"
  capture_transfers="$OUT/capture-transfers.tsv"
  : > "$capture_listing"
  : > "$capture_listing_error"
  : > "$capture_transfers"
  capture_listing_ok=false
  if adb exec-out run-as in.opentrack.india ls -1 files/evidence \
      > "$capture_listing" 2> "$capture_listing_error"; then
    capture_listing_ok=true
  fi
  listing=$(tr -d '\r' < "$capture_listing")
  while IFS= read -r name; do
    [[ "$name" =~ ^wimt-compare-[0-9]{4,5}-(before|app-live|app-details|web-cache|web-details|final)\.(png|xml)$ ]] || continue
    destination="$OUT/screens/$name"
    if adb exec-out run-as in.opentrack.india cat "files/evidence/$name" \
        > "$destination" 2>> "$capture_listing_error"; then
      bytes=$(wc -c < "$destination" | tr -d ' ')
      if [ "${bytes:-0}" -gt 0 ]; then
        printf '%s\tcopied\t%s\n' "$name" "$bytes" >> "$capture_transfers"
      else
        rm -f "$destination"
        printf '%s\tempty\t0\n' "$name" >> "$capture_transfers"
      fi
    else
      rm -f "$destination"
      printf '%s\tfailed\t0\n' "$name" >> "$capture_transfers"
    fi
  done <<< "$listing"
  adb logcat -d -s WimtMultiTrain:I > "$OUT/device-log.txt" 2>/dev/null || true
  # The full event diff can exceed logcat's per-message limit. Recover one JSONL record
  # written by the installed test under the target app UID, then feed it to the same verifier.
  if adb exec-out run-as in.opentrack.india cat \
      files/evidence/wimt-comparison-attempts.jsonl > "$OUT/attempts.jsonl" 2>/dev/null; then
    while IFS= read -r payload; do
      [ -n "$payload" ] || continue
      printf 'WIMT_MULTI_ATTEMPT_JSON=%s\n' "$payload" >> "$OUT/device-log.txt"
    done < "$OUT/attempts.jsonl"
  else
    : > "$OUT/attempts.jsonl"
  fi
  python3 tools/ci/wimt_capture_recovery.py --evidence "$OUT" \
      --listing-succeeded "$capture_listing_ok" \
      > "$OUT/capture-recovery.json" || \
    printf '%s\n' '{"available":false,"reason":"capture recovery summary failed"}' \
      > "$OUT/capture-recovery.json"
  verify_rc=1
  if python3 tools/ci/verify_wimt_live_comparison_screens.py "$OUT" \
       --device-log "$OUT/device-log.txt" \
       --capture-recovery "$OUT/capture-recovery.json" \
       > "$OUT/screen-verification.json" 2> "$OUT/screen-verification-error.txt"; then
    verify_rc=0
  else
    python3 - "$OUT" <<'PY'
import json
import sys
from pathlib import Path

out = Path(sys.argv[1])
try:
    recovery = json.loads((out / "capture-recovery.json").read_text(encoding="utf-8"))
except (OSError, json.JSONDecodeError):
    recovery = {"available": False}
try:
    error = (out / "screen-verification-error.txt").read_text(
        encoding="utf-8", errors="replace").strip()
except OSError:
    error = "screenshot verifier failed without an error detail"
result = {
    "verified": False,
    "verificationStatus": "VERIFICATION_ERROR",
    "verificationReason": "The installed-app capture verifier raised an error; no screenshot gate passed.",
    "verificationError": error[:500],
    "acceptedComparisonCount": 0,
    "minimumComparisons": 3,
    "comparisons": [],
    "captureRecovery": recovery,
    "pixelTextVerified": False,
}
(out / "screen-verification.json").write_text(
    json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY
    cat "$OUT/screen-verification-error.txt"
  fi
  if python3 tools/ci/wimt_live_comparison_report.py \
      --instrumentation "$OUT/instrumentation.txt" \
      --device-log "$OUT/device-log.txt" \
      --return-code "$rc" \
      --screen-verification "$OUT/screen-verification.json" \
      > "$OUT/result.json" 2> "$OUT/report-error.txt"; then
    report_rc=0
  else
    report_rc=$?
    cat "$OUT/report-error.txt" >&2
    if [ ! -s "$OUT/result.json" ]; then
      printf '%s\n' '{"accepted":false,"verdict":"REPORT_GENERATION_FAILED","reason":"comparison report failed; inspect report-error.txt and the raw attempt evidence"}' \
        > "$OUT/result.json"
    fi
  fi
  if [ "$rc" -eq 0 ] && { [ "$verify_rc" -ne 0 ] || [ "$report_rc" -ne 0 ]; }; then
    rc=5
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
# The installed test selects WIMT .in once per candidate and the explicit cache comparison only
# after a direct source response qualifies. It stops after three comparisons or any host refusal.
set +e
timeout 1500 adb shell am instrument -w -e class \
  in.opentrack.india.e2e.WimtLiveTrainWebComparisonAcceptanceTest#installedApkMustCompareThreeFreshSameTrainAndDateWimtRuns \
  in.opentrack.india.test/androidx.test.runner.AndroidJUnitRunner \
  > "$OUT/instrumentation.txt" 2>&1
rc=$?
set -e
if [ "$rc" -ne 0 ] || ! grep -q 'OK (1 test)' "$OUT/instrumentation.txt" || \
   grep -q 'FAILURES!!!' "$OUT/instrumentation.txt"; then
  tail -80 "$OUT/instrumentation.txt"
  exit 1
fi
# EXIT trap validates every completed comparison against its genuine app-window PNG/XML captures.
