#!/usr/bin/env bash
# Boot an Android emulator on a CI runner and leave it attached to adb.
#
# The constraint that shapes this whole script: GitHub-hosted runners have **no /dev/kvm**, so there is no
# hardware acceleration, and an x86 image under pure software emulation boots slowly or not at all. This
# script therefore (a) detects acceleration instead of assuming it, (b) picks flags that give a
# software-emulated boot a chance (no GPU, no snapshot, small heap, one core pair), and (c) exits **3**
# when the emulator could not be brought up, which lets a workflow distinguish "the product failed" from
# "this runner cannot host an emulator" — the difference between an actionable red and a misleading one.
#
# Env in:  API IMG ABI DEVICE AVD_NAME BOOT_TIMEOUT MEMORY CORES NO_GPU KEEP_LOGCAT
# Env out: E2E_ACCEL (kvm|off), written to $GITHUB_OUTPUT when present.
set -uo pipefail

API=${API:-34}
IMG=${IMG:-google_apis}
ABI=${ABI:-x86_64}
DEVICE=${DEVICE:-pixel_6}
AVD_NAME=${AVD_NAME:-e2e}
BOOT_TIMEOUT=${BOOT_TIMEOUT:-1500}
MEMORY=${MEMORY:-2048}
CORES=${CORES:-2}

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export PATH="$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$SDK/emulator:$PATH"

if [ -w /dev/kvm ] && [ -r /dev/kvm ]; then
  ACCEL=kvm
  echo "::notice::/dev/kvm is available - hardware acceleration on"
else
  ACCEL=off
  echo "::warning::no usable /dev/kvm on this runner - booting under software emulation, which is slow and may fail; a runner with KVM (or a self-hosted one) removes this whole class of problem"
fi
if [ -n "${GITHUB_OUTPUT:-}" ]; then
  echo "accel=$ACCEL" >> "$GITHUB_OUTPUT"
  echo "kvm=$([ "$ACCEL" = kvm ] && echo true || echo false)" >> "$GITHUB_OUTPUT"
fi

PKGS=("platform-tools" "emulator" "platforms;android-${API}" "system-images;android-${API};${IMG};${ABI}")
echo "== installing: ${PKGS[*]}"
for p in "${PKGS[@]}"; do
  if ! yes | sdkmanager --install "$p" > /tmp/sdkmanager.log 2>&1; then
    echo "::error::sdkmanager could not install $p"
    tail -25 /tmp/sdkmanager.log
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
if [ ! -f "$HOME/.android/avd/$AVD_NAME.avd/config.ini" ]; then
  echo "::error::AVD was not created"; cat /tmp/avd.log; exit 3
fi

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
