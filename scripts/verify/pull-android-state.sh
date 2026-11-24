#!/bin/sh
# Copies what Orature has saved on an Android device or emulator into a local folder, so
# `./gradlew :app-orature:dumpState -PdataDir=<folder>` can report it like a desktop one.
#
#   scripts/verify/pull-android-state.sh [out-dir] [adb serial]
#
# Needs a debug build (run-as only works on debuggable apps). Projects only: the installed
# source texts (rc/) are left behind, they are large and the dump skips them anyway.
set -eu
out="${1:-app-orature/build/verify-android/$(date +%Y%m%d-%H%M%S)}"
serial="${2:-}"
pkg=org.bibletranslationtools.orature
adb_() { if [ -n "$serial" ]; then adb -s "$serial" "$@"; else adb "$@"; fi; }

mkdir -p "$out"
# filesDir holds user/ (projects) and app/ (database, rc, logs). Skip app/rc.
adb_ exec-out run-as "$pkg" sh -c 'cd files && tar cf - --exclude=app/rc --exclude=app/temp .' | tar xf - -C "$out"
echo "VERIFY_DATA_DIR=$(cd "$out" && pwd)"
