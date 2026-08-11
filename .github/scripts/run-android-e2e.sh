#!/usr/bin/env bash
# Install Maestro, install the debug APK, grant mic, run the smoke orchestrator.
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

echo "Waiting for emulator"
bash .github/scripts/wait-for-emulator-ready.sh

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
# Single orchestrator (like BTT-Writer); --flatten-debug-output keeps artifacts shallow.
maestro test \
  --test-output-dir maestro-results \
  --debug-output maestro-results \
  --flatten-debug-output \
  .maestro/flows/smoke.yaml
STATUS=$?
set -e

exit "$STATUS"
