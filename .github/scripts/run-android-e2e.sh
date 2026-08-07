#!/usr/bin/env bash
# Run connected Android e2e, then pull/upload any timeout screenshots to tmpfiles.org.
set -u

bash .github/scripts/wait-for-emulator-ready.sh

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
