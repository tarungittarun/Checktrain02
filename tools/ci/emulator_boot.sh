#!/usr/bin/env bash
# Boot an Android emulator on a CI runner and leave it attached to adb.
#
# The constraint that shapes this script is acceleration. An x86 system image *requires* it - QEMU2 will
# refuse to start an x86 AVD under pure software emulation on an x86 host - so the useful behaviour is to
# detect /dev/kvm (present on GitHub's ubuntu runners, absent on many container and self-hosted ones),
# repair its permissions if the runner user cannot open it, and say "this runner cannot boot this image"
# in three seconds rather than after a twenty-five minute timeout that cannot succeed.
#
# Exit codes: 0 booted and ready; 3 no emulator here (missing acceleration, install failure, boot timeout).
# A workflow should treat 3 as "untested, with a reason" and never as a pass, and never as a product bug.
#
# Env in:  API IMG ABI DEVICE AVD_NAME BOOT_TIMEOUT MEMORY CORES NO_GPU KEEP_LOGCAT
# Env out: E2E_ACCEL (kvm|off), written to $GITHUB_OUTPUT when present.
set -uo pipefail

API=${API:-34}
IMG=${IMG:-google_apis}
ABI=${ABI:-x86_64}
DEVICE=${DEVICE:-pixel_6}
AVD_NAME=${AVD_NAME:-e2e}
# A cold API-34 x86_64 first boot on a 2 vCPU runner with software GL is slow, and 2 GB of heap makes it
# slower still (dex2oat thrashes), so the budget is generous and the memory is what the runner can actually
# give: a row that times out is reported as untested, which is a worse outcome than a row that takes nine
# minutes. Both numbers are env-overridable so a self-hosted runner can tighten them.
BOOT_TIMEOUT=${BOOT_TIMEOUT:-2400}
MEMORY=${MEMORY:-4096}
CORES=${CORES:-2}

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export PATH="$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$SDK/emulator:$PATH"

# Some runner images own /dev/kvm as root:kvm with mode 0660 and do not add the job user to the kvm group.
# That reads as "no acceleration" and silently costs the whole row, so fix it before deciding.
if [ ! -w /dev/kvm ] && [ -e /dev/kvm ] && command -v sudo >/dev/null 2>&1; then
  sudo -n chmod 666 /dev/kvm >/dev/null 2>&1 || true
fi
if [ -w /dev/kvm ] && [ -r /dev/kvm ]; then
  ACCEL=kvm
  echo "::notice::/dev/kvm is available - hardware acceleration on"
else
  ACCEL=off
  echo "::warning::no usable /dev/kvm on this runner"
  case "$ABI" in
    x86*)
      # An x86 image cannot boot without it, so waiting is not caution, it is wasted CI time. Say which
      # knob to turn instead: a KVM-capable runner, or an arm64 image on an arm64 host.
      echo "::error::$ABI needs hardware acceleration; this runner has none, so this row is untested rather than failed. Use a KVM-capable runner (GitHub's ubuntu-latest qualifies) or an arm64 image on arm64 hardware."
      exit 3
      ;;
  esac
fi
if [ -n "${GITHUB_OUTPUT:-}" ]; then
  echo "accel=$ACCEL" >> "$GITHUB_OUTPUT"
  echo "kvm=$([ "$ACCEL" = kvm ] && echo true || echo false)" >> "$GITHUB_OUTPUT"
fi

PKGS=("platform-tools" "emulator" "platforms;android-${API}" "system-images;android-${API};${IMG};${ABI}")
echo "== installing: ${PKGS[*]}"

# What each package has to leave on disk for the run to be able to continue. The list is deliberately
# checked *after* sdkmanager rather than trusting its exit code: on GitHub's images platform-tools, the
# emulator and android-34 are already installed, `sdkmanager` then closes its stdin early, and `yes` dies
# of a broken pipe - which, under `pipefail`, reads as "the install failed" and was what killed every row of
# run 38120714896. A component that exists is a component that is installed, whatever the tool returned.
path_ok() {
  case "$1" in
    platform-tools) [ -x "$SDK/platform-tools/adb" ] ;;
    emulator) [ -x "$SDK/emulator/emulator" ] ;;
    platforms*) [ -d "$SDK/platforms/android-${API}" ] ;;
    system-images*) [ -d "$SDK/system-images/android-${API}/${IMG}/${ABI}" ] ;;
    *) false ;;
  esac
}

sdkmanager_says() {
  # One line per progress tick, with \r, so a raw tail of that log is 40 kB of bars and buries the sentence
  # that matters. Progress is dropped, errors are kept.
  tr '\r' '\n' < /tmp/sdkmanager.log 2>/dev/null \
    | grep -vE "Loading (package|local repository)|Fetch remote repository|Computing updates|Unzipping|Downloading|%\]$" \
    | grep -v '^[[:space:]]*$' | tail -12
}

install_pkg() {
  local pkg=$1 rc=0
  path_ok "$pkg" && { echo "   $pkg already present"; return 0; }
  yes 2>/dev/null | sdkmanager --install "$pkg" > /tmp/sdkmanager.log 2>&1
  rc=${PIPESTATUS[1]}
  # sdkmanager's own verdict first. Only if it *says* it failed do we look at the filesystem again, because
  # a path guess that does not match the SDK's real layout must not be able to turn a successful install
  # into "this api level has no image" - which is what cost run 38121167143 the release and density rows.
  [ "$rc" = 0 ] && return 0
  if path_ok "$pkg"; then echo "   $pkg installed (exit $rc, files present)"; return 0; fi
  echo "sdkmanager exit $rc for $pkg:"; sdkmanager_says
  return 1
}

for p in "${PKGS[@]}"; do
  if ! install_pkg "$p"; then
    case "$p" in
      system-images*)
        # Not every API level published an x86_64 google_apis image (android-24 is a common offender), and
        # a 32-bit system image runs the app just as well for these purposes. Falling back keeps the minSdk
        # row a real row instead of a packaging accident in the image list.
        echo "::notice::no system image for $IMG/$ABI at api ${API} - falling back to x86"
        ABI=x86
        if ! install_pkg "system-images;android-${API};${IMG};x86"; then
          echo "::error::sdkmanager could not install any system image for api ${API} (${IMG})"
          tail -25 /tmp/sdkmanager.log
          exit 3
        fi
        continue
        ;;
    esac
    echo "::error::sdkmanager could not install $p"
    sdkmanager_says
    exit 3
  fi
done

echo "== creating AVD $AVD_NAME (api $API, $IMG/$ABI, device $DEVICE)"
if [ "$ACCEL" = off ]; then
  # `avdmanager create avd -d <device>` needs a hardware profile; on a minimal SDK the profile list can be
  # empty, in which case an unspecified device is the only thing that works.
  echo no | avdmanager create avd -n "$AVD_NAME" -k "system-images;android-${API};${IMG};${ABI}" -d "$DEVICE" >/tmp/avd.log 2>&1 \
    || echo no | avdmanager create avd -n "$AVD_NAME" -k "system-images;android-${API};${IMG};${ABI}" >>/tmp/avd.log 2>&1
else
  echo no | avdmanager create avd -n "$AVD_NAME" -k "system-images;android-${API};${IMG};${ABI}" -d "$DEVICE" >/tmp/avd.log 2>&1
fi
# Where the AVD actually lives is decided by the tools' own precedence: ANDROID_AVD_HOME, then
# $ANDROID_SDK_HOME/.android/avd (the deprecated variable is still honoured, and CI images set it), then
# $HOME/.android/avd. Assuming the last one is what turned run 38121489777's four rows red: `avdmanager`
# created the image and reported nothing, and the check looked in the wrong home. So search instead of
# guessing, and treat "not found" as a warning rather than a verdict - the emulator resolving `-avd` is the
# real test of whether an AVD exists, and its failure is already captured by the boot timeout below.
AVD_HOME=${ANDROID_AVD_HOME:-}
[ -z "$AVD_HOME" ] && [ -n "${ANDROID_SDK_HOME:-}" ] && AVD_HOME="$ANDROID_SDK_HOME/.android/avd"
[ -z "$AVD_HOME" ] && AVD_HOME="$HOME/.android/avd"
if [ ! -f "$AVD_HOME/$AVD_NAME.avd/config.ini" ]; then
  found=$(find "$SDK" "$HOME/.android" -maxdepth 7 -name config.ini -path "*/$AVD_NAME.avd/*" 2>/dev/null | head -1)
  if [ -n "$found" ]; then
    echo "   AVD found outside the default home: $found"
  else
    echo "::warning::no $AVD_NAME.avd/config.ini under $AVD_HOME (or anywhere in the SDK) - continuing, because the emulator's own resolution decides; avdmanager said:"
    tr '\r' '\n' < /tmp/avd.log | grep -v '^[[:space:]]*$' | tail -12
  fi
fi
echo "== avd env: HOME=$HOME ANDROID_AVD_HOME=${ANDROID_AVD_HOME:-unset} ANDROID_SDK_HOME=${ANDROID_SDK_HOME:-unset}"
ls -la "$AVD_HOME" 2>/dev/null | head -6 || echo "   (no $AVD_HOME directory)"

EMFLAGS=(-avd "$AVD_NAME" -no-window -no-audio -no-boot-anim -no-snapshot -no-snapshot-save -read-only
  -netfast -verbose -cores "$CORES" -memory "$MEMORY")
if [ "$ACCEL" = kvm ]; then
  EMFLAGS+=(-gpu swiftshader_indirect -accel on)
else
  # No GPU at all, and no `-accel` argument to argue with: `-no-accel` is the explicit "software only".
  EMFLAGS+=(-gpu off -no-accel)
fi

adb start-server >/dev/null 2>&1 || true
echo "== emulator ${EMFLAGS[*]}"
nohup "$SDK/emulator/emulator" "${EMFLAGS[@]}" > /tmp/emulator.log 2>&1 &
EM_PID=$!
echo "emulator pid $EM_PID"

# Two-stage wait: first for adb to see *a* device, then for the framework to report boot completion. A
# single `wait-for-device` returns while the screen is still `sh`, and everything after it fails uselessly.
timeout 300 adb wait-for-device || echo "::warning::adb did not report a device within 300 s; continuing to poll"
start=$(date +%s)
while :; do
  now=$(date +%s)
  if [ $((now - start)) -gt "$BOOT_TIMEOUT" ]; then
    echo "::error::emulator did not finish booting in ${BOOT_TIMEOUT}s under accel=$ACCEL (api $API, $IMG/$ABI)"
    echo "--- emulator.log (tail) ---"; tail -40 /tmp/emulator.log
    echo "--- ps ---"; ps -o pid,etime,rss,args -p "$EM_PID" 2>&1 | tail -3
    exit 3
  fi
  if kill -0 "$EM_PID" 2>/dev/null; then
    booted=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
    [ "$booted" = "1" ] && break
  else
    echo "::error::the emulator process exited at $((now - start))s - see /tmp/emulator.log"
    tail -30 /tmp/emulator.log
    exit 3
  fi
  # A heartbeat, once a minute: "the framework is still coming up" and "nothing has moved for 20 minutes"
  # look the same from the outside, and the difference decides whether to wait, to enlarge the budget, or
  # to conclude the runner cannot host this image.
  if [ $(((now - start) % 60)) -lt 6 ] && [ "$now" != "$start" ]; then
    echo "   [+$((now - start))s] boot_completed=${booted:-?} rss=$(ps -o rss= -p "$EM_PID" 2>/dev/null | tr -d ' ' || echo ?)kB last=$(tr '\r' '\n' < /tmp/emulator.log 2>/dev/null | grep -v '^[[:space:]]*$' | tail -1 | cut -c1-90)"
  fi
  sleep 5
done
echo "== booted in $(( $(date +%s) - start ))s (accel=$ACCEL)"

# Determinism the suite depends on: no animations (they make `Until.idle` and every wait longer than the
# thing being measured), no soft-keyboard autocomplete rewriting typed text, and no lock screen.
adb shell settings put global window_animation_scale 0 >/dev/null
adb shell settings put global transition_animation_scale 0 >/dev/null
adb shell settings put global animator_duration_scale 0 >/dev/null
adb shell settings put secure show_ime_with_hard_keyboard 1 >/dev/null || true
adb shell input keyevent 82 >/dev/null 2>&1 || true
adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
adb logcat -c || true
adb shell dumpsys dropbox --clear >/dev/null 2>&1 || true
echo "emulator ready: $(adb devices | tail -n +2 | head -1)"
