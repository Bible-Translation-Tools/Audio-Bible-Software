#!/usr/bin/env bash
# Wait until the AVD is past boot + launcher settle (from BTT-Writer CI).
set -euo pipefail

adb wait-for-device
adb shell 'until [[ "$(getprop sys.boot_completed)" == "1" ]]; do sleep 2; done'

# Let system services settle; reduces cold-start ANRs on CI.
sleep 20

adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard 2>/dev/null || true
# Do NOT send KEYCODE_HOME — that foregrounds Pixel Launcher, which often ANRs on CI
# and leaves "isn't responding" on top of the app under test.

# Suppress ANR / crash dialogs where the platform honors these globals.
adb shell settings put global hide_error_dialogs 1 || true
adb shell settings put global anr_show_background 0 || true

bash "$(dirname "$0")/dismiss-anr.sh" || true

echo "Emulator boot completed and keyguard dismissed"
