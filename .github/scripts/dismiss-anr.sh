#!/usr/bin/env bash
# Dismiss system ANR dialogs (e.g. "Pixel Launcher isn't responding") via ui dump + tap.
# Optional Maestro/UiAutomator taps often miss these system windows on CI.
set -eu

DUMP_REMOTE="/sdcard/window_dump.xml"
DUMP_LOCAL="$(mktemp)"
cleanup() { rm -f "${DUMP_LOCAL}"; }
trap cleanup EXIT

adb shell uiautomator dump "${DUMP_REMOTE}" >/dev/null 2>&1 || true
adb pull "${DUMP_REMOTE}" "${DUMP_LOCAL}" >/dev/null 2>&1 || true

if [[ ! -s "${DUMP_LOCAL}" ]]; then
  echo "ANR dismiss: no ui dump"
  exit 0
fi

if ! grep -Eqi "isn't responding|aerr_wait|aerr_close|Application Not Responding" "${DUMP_LOCAL}"; then
  echo "ANR dismiss: no ANR dialog in hierarchy"
  exit 0
fi

echo "ANR dismiss: dialog detected, tapping Wait if present"

# Prefer android:id/aerr_wait, then text="Wait".
tap_bounds() {
  local line="$1"
  if [[ "${line}" =~ bounds=\"\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]\" ]]; then
    local x=$(( (BASH_REMATCH[1] + BASH_REMATCH[3]) / 2 ))
    local y=$(( (BASH_REMATCH[2] + BASH_REMATCH[4]) / 2 ))
    echo "ANR dismiss: input tap ${x} ${y}"
    adb shell input tap "${x}" "${y}" || true
    return 0
  fi
  return 1
}

line="$(grep -E 'resource-id="android:id/aerr_wait"' "${DUMP_LOCAL}" | head -n 1 || true)"
if [[ -n "${line}" ]] && tap_bounds "${line}"; then
  exit 0
fi

line="$(grep -E 'text="Wait"' "${DUMP_LOCAL}" | head -n 1 || true)"
if [[ -n "${line}" ]] && tap_bounds "${line}"; then
  exit 0
fi

# Fallback: DPAD + ENTER (often lands on Wait).
echo "ANR dismiss: falling back to DPAD_DOWN + ENTER"
adb shell input keyevent KEYCODE_DPAD_DOWN || true
adb shell input keyevent KEYCODE_ENTER || true
