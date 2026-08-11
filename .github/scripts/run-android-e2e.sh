#!/usr/bin/env bash
# Install Maestro, install the debug APK, grant mic, run smoke flows.
set -eu

APP_ID="${APP_ID:-org.bibletranslationtools.recorder2}"
APK_PATH="${APK_PATH:-}"
if [[ -z "${APK_PATH}" ]]; then
  APK_PATH="$(ls -1 app-recorder/build/outputs/apk/debug/*.apk | head -n 1)"
fi
if [[ -z "${APK_PATH}" || ! -f "${APK_PATH}" ]]; then
  echo "Debug APK not found under app-recorder/build/outputs/apk/debug/" >&2
  exit 1
fi

echo "Installing Maestro CLI"
curl -Ls "https://get.maestro.mobile.dev" | bash
export PATH="${HOME}/.maestro/bin:${PATH}"
maestro --version

echo "Installing ${APK_PATH}"
adb install -r -t "${APK_PATH}"

echo "Granting RECORD_AUDIO to ${APP_ID}"
adb shell pm grant "${APP_ID}" android.permission.RECORD_AUDIO || true

mkdir -p maestro-results
set +e
# Prefer --test-output-dir over --format junit (junit report path has required Cloud API key in some CLI versions).
# Screenshots land in maestro-results/ and are uploaded as the recorder-maestro-results artifact on failure.
maestro test .maestro/ --test-output-dir maestro-results --debug-output maestro-results
STATUS=$?
set -e

exit "$STATUS"
