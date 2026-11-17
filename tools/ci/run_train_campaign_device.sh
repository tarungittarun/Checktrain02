#!/usr/bin/env bash
# One assigned-train installed-app observation run on one isolated GitHub Actions emulator.
set -Eeuo pipefail

OUT=${CAMPAIGN_EVIDENCE_DIR:-campaign-evidence/slot-unknown}
ASSIGNMENT=${CAMPAIGN_ASSIGNMENT:-$OUT/assignment.json}
mkdir -p "$OUT/screens"
: > "$OUT/instrumentation.txt"
: > "$OUT/device-log.txt"

SLOT="unknown"
TRAIN_NO="unknown"
RUN_DATE="unknown"
RUN_DAY=""
DEPARTURE_MINUTE=""
ARRIVAL_MINUTE=""
SCHEDULE_ELIGIBILITY="unknown"
INSTRUMENTATION_RC="not-started"
EMULATOR_READY=false

if [ -f "$ASSIGNMENT" ]; then
  SLOT=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["slot"])' "$ASSIGNMENT")
  TRAIN_NO=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["trainNo"])' "$ASSIGNMENT")
  RUN_DATE=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["runDate"])' "$ASSIGNMENT")
  RUN_DAY=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["originEpochDay"])' "$ASSIGNMENT")
  DEPARTURE_MINUTE=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["scheduledDepartureMinute"])' "$ASSIGNMENT")
  ARRIVAL_MINUTE=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["scheduledArrivalMinute"])' "$ASSIGNMENT")
  SCHEDULE_ELIGIBILITY=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["scheduleEligibility"])' "$ASSIGNMENT")
fi

write_fallback_result() {
  local status="$1"
  local reason="$2"
  python3 - "$OUT/slot-result.json" "$status" "$reason" "$SLOT" "$TRAIN_NO" "$RUN_DATE" \
      "$INSTRUMENTATION_RC" "$EMULATOR_READY" <<'PY'
import json, sys
path, status, reason, slot, train, date, instrumentation, emulator = sys.argv[1:]
with open(path, "w", encoding="utf-8") as stream:
    json.dump({
        "schemaVersion": 1,
        "slot": int(slot) if slot.isdigit() else None,
        "status": status,
        "trainNo": train if train != "unknown" else None,
        "runDate": date if date != "unknown" else None,
        "runnerInstrumentationExitCode": instrumentation,
        "emulatorBooted": emulator == "true",
        "reason": reason[:500],
        "passed": False,
    }, stream, indent=2, sort_keys=True)
    stream.write("\n")
PY
}

collect_device_evidence() {
  local result_ok=0
  local observations_ok=0
  local tmp
  export PATH="${ANDROID_HOME:-/usr/local/lib/android/sdk}/platform-tools:$PATH"
  if ! command -v adb >/dev/null 2>&1 || ! adb get-state >/dev/null 2>&1; then
    return 1
  fi

  tmp="$OUT/slot-result.json.tmp"
  if adb exec-out run-as in.opentrack.india cat files/campaign/result.json \
      > "$tmp" 2>/dev/null && \
      python3 -c 'import json,sys; value=json.load(open(sys.argv[1], encoding="utf-8")); sys.exit(0 if isinstance(value, dict) else 1)' "$tmp"; then
    mv "$tmp" "$OUT/slot-result.json"
    result_ok=1
  else
    rm -f "$tmp"
  fi

  tmp="$OUT/observations.jsonl.tmp"
  if adb exec-out run-as in.opentrack.india cat files/campaign/observations.jsonl \
      > "$tmp" 2>/dev/null && [ -s "$tmp" ]; then
    mv "$tmp" "$OUT/observations.jsonl"
    observations_ok=1
  else
    rm -f "$tmp"
  fi

  mkdir -p "$OUT/screens"
  for name in campaign-minute-00 campaign-minute-15 campaign-minute-30 campaign-minute-45; do
    tmp="$OUT/screens/$name.png.tmp"
    if adb exec-out run-as in.opentrack.india cat "files/campaign/screens/$name.png" \
        > "$tmp" 2>/dev/null && [ "$(wc -c < "$tmp")" -gt 1000 ]; then
      mv "$tmp" "$OUT/screens/$name.png"
    else
      rm -f "$tmp" "$OUT/screens/$name.png"
    fi
    tmp="$OUT/screens/$name.xml.tmp"
    if adb exec-out run-as in.opentrack.india cat "files/campaign/screens/$name.xml" \
        > "$tmp" 2>/dev/null && [ "$(wc -c < "$tmp")" -gt 100 ]; then
      mv "$tmp" "$OUT/screens/$name.xml"
    else
      rm -f "$tmp" "$OUT/screens/$name.xml"
    fi
  done
  adb logcat -d -s TrainCampaign:I TrainCampaign:E > "$OUT/device-log.txt" 2>/dev/null || true
  [ "$result_ok" -eq 1 ] && [ "$observations_ok" -eq 1 ]
}

finish() {
  local rc=$?
  set +e
  collect_device_evidence >/dev/null 2>&1 || true
  if [ ! -s "$OUT/slot-result.json" ]; then
    write_fallback_result "DEVICE_RUN_FAILED_OR_NO_RESULT" \
      "The emulator test did not leave a readable in-app result; see runner logs and instrumentation output."
  fi
  printf 'instrumentationExitCode=%s\n' "$INSTRUMENTATION_RC" > "$OUT/runner-exit.txt"
  if [ "$rc" -ne 0 ] && [ "$rc" -ne 1 ]; then
    printf 'runnerShellExitCode=%s\n' "$rc" >> "$OUT/runner-exit.txt"
  fi
  trap - EXIT
  exit "$rc"
}
trap finish EXIT

case "$TRAIN_NO" in
  ''|*[^0-9]*) write_fallback_result "ASSIGNMENT_INVALID" "No valid assigned train number was provided."; exit 2 ;;
esac
if [ "${#TRAIN_NO}" -lt 4 ] || [ "${#TRAIN_NO}" -gt 5 ]; then
  write_fallback_result "ASSIGNMENT_INVALID" "Assigned train number must contain four or five digits."
  exit 2
fi

check_eligibility() {
  local label="$1"
  local path="$OUT/eligibility-$label.json"
  local status=0
  python3 tools/ci/verify_campaign_assignment.py \
    --assignment "$ASSIGNMENT" --output "$path" || status=$?
  if [ "$status" -gt 2 ]; then
    write_fallback_result "ELIGIBILITY_CHECK_FAILED" "Eligibility verifier failed unexpectedly at $label."
    return 1
  fi
  python3 - "$path" <<'PY'
import json, sys
result = json.load(open(sys.argv[1], encoding="utf-8"))
if result.get("eligible") is not True:
    print("ineligible:", ";".join(result.get("reasons", [])))
    raise SystemExit(2)
print("eligible:", result.get("scheduleEligibilityAtStart"),
      "preflightAgeSeconds=", result.get("preflightSourceStampAgeSecondsAtStart"))
PY
}

set +e
check_eligibility "before-emulator"
eligibility_rc=$?
set -e
if [ "$eligibility_rc" -ne 0 ]; then
  write_fallback_result "NOT_RUN_INELIGIBLE_AT_DEVICE_START" \
    "The assigned train/date or fresh WIMT .in preflight evidence no longer qualified before emulator boot."
  exit 1
fi

APP_APK=${CAMPAIGN_APP_APK:-campaign-apks/app-debug.apk}
TEST_APK=${CAMPAIGN_TEST_APK:-campaign-apks/app-debug-androidTest.apk}
if [ ! -s "$APP_APK" ] || [ ! -s "$TEST_APK" ]; then
  write_fallback_result "APK_ARTIFACT_MISSING" "The same-run debug app/instrumentation APK pair was unavailable; emulator boot was skipped."
  exit 4
fi

export API=34 IMG=google_apis ABI=x86_64 DEVICE=pixel_6 BOOT_TIMEOUT=600
if ! bash tools/ci/emulator_boot.sh > "$OUT/boot.txt" 2>&1; then
  tail -40 "$OUT/boot.txt"
  EMULATOR_READY=false
  write_fallback_result "EMULATOR_BOOT_FAILED" "Android emulator did not finish booting."
  exit 3
fi
EMULATOR_READY=true
export PATH="${ANDROID_HOME:-/usr/local/lib/android/sdk}/platform-tools:$PATH"
adb shell svc power stayon true >/dev/null 2>&1 || true
adb shell settings put global stay_on_while_plugged_in 3 >/dev/null 2>&1 || true
adb shell settings put system screen_off_timeout 2147483647 >/dev/null 2>&1 || true

adb install -r -g "$APP_APK"
adb install -r "$TEST_APK"
installed=$(adb shell pm path in.opentrack.india | tr -d '\r' | sed -n 's/^package://p' | head -1)
installed_test=$(adb shell pm path in.opentrack.india.test | tr -d '\r' | sed -n 's/^package://p' | head -1)
[ -n "$installed" ] || { write_fallback_result "APP_INSTALL_FAILED" "PackageManager did not find the installed app."; exit 5; }
[ -n "$installed_test" ] || { write_fallback_result "TEST_INSTALL_FAILED" "PackageManager did not find the installed instrumentation APK."; exit 5; }
adb pull "$installed" /tmp/campaign-installed-app.apk >/dev/null
adb pull "$installed_test" /tmp/campaign-installed-test.apk >/dev/null
{
  printf 'built app %s\n' "$(sha256sum "$APP_APK")"
  printf 'installed app %s\n' "$(sha256sum /tmp/campaign-installed-app.apk)"
  printf 'built instrumentation %s\n' "$(sha256sum "$TEST_APK")"
  printf 'installed instrumentation %s\n' "$(sha256sum /tmp/campaign-installed-test.apk)"
} | tee "$OUT/apk-install-proof.txt"
cmp "$APP_APK" /tmp/campaign-installed-app.apk
cmp "$TEST_APK" /tmp/campaign-installed-test.apk
rm -f /tmp/campaign-installed-app.apk /tmp/campaign-installed-test.apk
adb shell pm clear in.opentrack.india
adb logcat -c

# Emulator boot can queue a matrix job long enough for its timetable window/feed stamp to expire.
set +e
check_eligibility "after-emulator-boot"
eligibility_rc=$?
set -e
if [ "$eligibility_rc" -ne 0 ]; then
  write_fallback_result "NOT_RUN_INELIGIBLE_AFTER_EMULATOR_BOOT" \
    "The assigned train/date or fresh WIMT .in evidence expired while the emulator was preparing."
  exit 1
fi

printf 'Assigned slot=%s train=%s runDate=%s eligibility=%s\n' \
  "$SLOT" "$TRAIN_NO" "$RUN_DATE" "$SCHEDULE_ELIGIBILITY" | tee "$OUT/assignment.txt"

set +e
timeout --signal=TERM --kill-after=60 4800s adb shell am instrument -w \
  -e class in.opentrack.india.e2e.TrainCampaignObservationTest#assignedTrainIsObservedAtMinuteZeroThrough45OnOneEmulator \
  -e campaign_slot "$SLOT" \
  -e campaign_train_no "$TRAIN_NO" \
  -e campaign_run_date "$RUN_DATE" \
  -e campaign_departure_minute "$DEPARTURE_MINUTE" \
  -e campaign_arrival_minute "$ARRIVAL_MINUTE" \
  -e campaign_schedule_eligibility "$SCHEDULE_ELIGIBILITY" \
  -e campaign_duration_minutes 45 \
  in.opentrack.india.test/androidx.test.runner.AndroidJUnitRunner \
  > "$OUT/instrumentation.txt" 2>&1
INSTRUMENTATION_RC=$?
set -e
if [ "$INSTRUMENTATION_RC" -ne 0 ] || \
   ! grep -q 'OK (1 test)' "$OUT/instrumentation.txt" || \
   grep -q 'FAILURES!!!' "$OUT/instrumentation.txt"; then
  tail -80 "$OUT/instrumentation.txt"
  exit 1
fi
# Copy the in-app result BEFORE validating it. The EXIT trap is only a failure-safe collector;
# the previous runner validated slot-result.json before this collection happened.
collect_device_evidence || true
python3 tools/ci/verify_campaign_runner_result.py \
  --result "$OUT/slot-result.json" \
  --observations "$OUT/observations.jsonl" \
  --instrumentation "$OUT/instrumentation.txt" \
  --instrumentation-exit-code "$INSTRUMENTATION_RC" \
  --evidence-dir "$OUT" \
  --assignment "$ASSIGNMENT" \
  --eligibility-before "$OUT/eligibility-before-emulator.json" \
  --eligibility-after "$OUT/eligibility-after-emulator-boot.json"
