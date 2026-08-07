#!/usr/bin/env bash
# Run connected Android e2e, then pull/upload any timeout screenshots to tmpfiles.org.
set -u

bash .github/scripts/wait-for-emulator-ready.sh

# Clear leftovers from a poisoned AVD cache or a prior install that crashed before
# leaveApksInstalledAfterRun=false could uninstall (avoids UPDATE_INCOMPATIBLE).
adb uninstall org.bibletranslationtools.recorder2 2>/dev/null || true
adb uninstall org.bibletranslationtools.recorder2.test 2>/dev/null || true

gradle :app-recorder:connectedDebugAndroidTest -PminimalGlSources=true --stacktrace
STATUS=$?

mkdir -p e2e-screenshots
adb pull /data/local/tmp/e2e-screenshots/. e2e-screenshots/ 2>/dev/null || true

shopt -s nullglob
for f in e2e-screenshots/*.png; do
  echo "Uploading $f to tmpfiles.org"
  RESP=$(curl -fsS -F "file=@${f}" -F "expire=86400" https://tmpfiles.org/api/v1/upload || true)
  echo "tmpfiles response: ${RESP}"
  echo "$RESP" | sed -n 's/.*"url":"\([^"]*\)".*/VIEW=\1/p' || true
done

exit "$STATUS"
