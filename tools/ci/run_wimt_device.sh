#!/usr/bin/env bash
# Install the exact locally built APK and run ONE online, source-strict UI journey.
# Always retain screenshots and a bounded result, even after an emulator/test failure.
set -euo pipefail
mkdir -p wimt-device-evidence/screens
finish() {
  rc=$?
  set +e
  # A screenshot after instrumentation terminates is the Android LAUNCHER,
  # not app evidence. Never capture or publish that as a WIMT screenshot.
  adb shell uiautomator dump /sdcard/wimt-window.xml >/dev/null 2>&1 || true
  adb pull /sdcard/wimt-window.xml wimt-device-evidence/window.xml >/dev/null 2>&1 || true
  # Pull each *in-test* screenshot from the debuggable TARGET app's data dir.
  # A missing file is a capture failure, not permission to use the launcher.
  for name in wimt-settings-diagnostics wimt-search-route-criteria wimt-search-route-results \
      wimt-before-selected-source wimt-source-picker-open wimt-after-selected-source \
      wimt-final-even-on-failure; do
    for ext in png xml; do
      adb exec-out run-as in.opentrack.india cat "files/evidence/$name.$ext" \
        > "wimt-device-evidence/screens/$name.$ext" 2>/dev/null || \
        rm -f "wimt-device-evidence/screens/$name.$ext"
    done
  done
  # Raw device diary is transient: never upload it or post CDN IDs, cookies or IPs.
  adb shell run-as in.opentrack.india cat files/diag/log.txt \
    > /tmp/wimt-device-private-diary.txt 2>/dev/null || true
  python3 tools/ci/wimt_device_report.py wimt-device-evidence/instrumentation.txt \
    /tmp/wimt-device-private-diary.txt "$rc" > wimt-device-evidence/result.json || true
  # Validate same-run screenshots against the result's origin date. A failed journey
  # still leaves its genuine captures for the diagnostic PDF, but never a pass record.
  if python3 tools/ci/verify_wimt_screens.py wimt-device-evidence --accepted \
    > wimt-device-evidence/screen-verification.json \
    2> wimt-device-evidence/screen-verification-error.txt; then
    rm -f wimt-device-evidence/screen-verification-diagnostic.json
    : # The strict verifier checked every stage, selected website and result date.
  else
    rm -f wimt-device-evidence/screen-verification.json
    cat wimt-device-evidence/screen-verification-error.txt
    # Preserve a separately labelled diagnostic inventory of genuine captures when
    # strict verification fails. It never feeds acceptance or APK publication.
    if python3 tools/ci/verify_wimt_screens.py wimt-device-evidence --diagnostic \
      > wimt-device-evidence/screen-verification-diagnostic.json \
      2> wimt-device-evidence/screen-verification-diagnostic-error.txt; then
      echo 'Diagnostic-only screenshot inventory retained; this does not satisfy acceptance.'
    else
      rm -f wimt-device-evidence/screen-verification-diagnostic.json
      cat wimt-device-evidence/screen-verification-diagnostic-error.txt
    fi
    if [ "$rc" -eq 0 ]; then
      rc=4 # Instrumentation alone is not an acceptance pass without strict UI evidence.
      python3 tools/ci/wimt_device_report.py wimt-device-evidence/instrumentation.txt \
        /tmp/wimt-device-private-diary.txt "$rc" > wimt-device-evidence/result.json || true
    fi
  fi
  rm -f /tmp/wimt-device-private-diary.txt
  cat wimt-device-evidence/result.json
  trap - EXIT
  exit "$rc"
}
trap finish EXIT
export API=34 IMG=google_apis ABI=x86_64 DEVICE=pixel_6 BOOT_TIMEOUT=360
bash tools/ci/emulator_boot.sh > wimt-device-evidence/boot.txt 2>&1 || {
  tail -20 wimt-device-evidence/boot.txt
  exit 3
}
export PATH="${ANDROID_HOME:-/usr/local/lib/android/sdk}/platform-tools:$PATH"
app_apk=app/build/outputs/apk/debug/app-debug.apk
test_apk=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
: "${GITHUB_SHA:?same-run source SHA required for APK identity check}"
python3 tools/ci/verify_acceptance_apk.py "$app_apk" "$app_apk" "$GITHUB_SHA" \
  | tee wimt-device-evidence/apk-preflight.txt
sha256sum "$app_apk" "$test_apk" > wimt-device-evidence/built-apk-sha256.txt
adb install -r -g "$app_apk"
adb install -r "$test_apk"
adb shell pm list instrumentation \
  | grep -F 'in.opentrack.india.test/androidx.test.runner.AndroidJUnitRunner'
installed_path=$(adb shell pm path in.opentrack.india | tr -d '\r' \
  | sed -n 's/^package://p' | head -1)
[ -n "$installed_path" ] || { echo '::error::Android PackageManager has no installed app APK'; exit 1; }
adb pull "$installed_path" /tmp/wimt-installed.apk >/dev/null
printf 'built %s\ninstalled %s\n' "$(sha256sum "$app_apk")" \
  "$(sha256sum /tmp/wimt-installed.apk)" > wimt-device-evidence/installed-apk-sha256.txt
cmp "$app_apk" /tmp/wimt-installed.apk || {
  echo '::error::installed base.apk differs from the exact APK built for this website acceptance run'
  exit 1
}
adb shell pm clear in.opentrack.india
# Android's instrumentation command can return zero even when tests fail; the
# report gate independently requires exactly one successful instrumented method.
# The website test has a 120-second single-request publication budget, in addition
# to route/context setup; do not kill it at the former 180-second outer limit.
set +e
timeout 300 adb shell am instrument -w -e class \
  in.opentrack.india.e2e.WimtWebsiteLiveAcceptanceTest#websiteRefreshMustPaintARealSourceQualifiedPastCardFor16227 \
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
