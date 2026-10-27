#!/usr/bin/env bash
# Saves, and puts back, everything the old BTT-Recorder keeps on a device, so the legacy migration
# can be tested again from the same starting point without recording anything again.
#
#   scripts/old-recorder-snapshot.sh backup  <device-serial> <snapshot-dir>
#   scripts/old-recorder-snapshot.sh restore <device-serial> <snapshot-dir>
#
# backup  needs the OLD app installed, and a debuggable build of it (run-as reads its private data).
#         It saves the old app's APK, its private files (database, preferences, files/) and its
#         external files (translations/ and the rest of Android/data/<app>/files).
# restore UNINSTALLS whatever is installed under the app id (after a test, the new app and every
#         project it holds), reinstalls the saved old APK, and puts its files back.
#
# The device serial comes from `adb devices -l`; it is required so nothing reaches the wrong device.
set -euo pipefail

PKG=bible.translationtools.recorder
EXTERNAL=/sdcard/Android/data/$PKG/files
STAGING=/data/local/tmp/old-recorder-snapshot

usage() { sed -n '2,15p' "$0"; exit 1; }
[[ $# -eq 3 ]] || usage
action=$1 serial=$2 dir=$3

# stdin from /dev/null: adb otherwise reads it, and swallows the file lists the loops below read.
device() { adb -s "$serial" "$@" </dev/null; }
as_app() { device exec-out run-as "$PKG" "$@"; }

installed_version_code() {
  device shell dumpsys package "$PKG" | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1
}

backup() {
  local code
  code=$(installed_version_code)
  [[ -n "$code" ]] || { echo "Nothing is installed as $PKG." >&2; exit 1; }
  # The new app's codes start at 101 (versionCode offset); the old app's last was 56.
  (( code < 100 )) || { echo "$PKG is versionCode $code: that's the new app, not the old one. Restore first." >&2; exit 1; }
  # A folder with files in it may be a snapshot; an empty one (an earlier, failed run) is fine,
  # and so is the .DS_Store Finder leaves in a folder it has shown.
  [[ -z "$(find "$dir" -type f ! -name .DS_Store 2>/dev/null | head -1)" ]] || { echo "$dir already holds files; choose a new folder so no snapshot is overwritten." >&2; exit 1; }
  mkdir -p "$dir/internal"

  local apks
  apks=$(device shell pm path "$PKG" | sed -n 's/^package://p' | tr -d '\r')
  [[ $(wc -l <<< "$apks") -eq 1 ]] || { echo "The old app is installed as split APKs; install it as one APK first." >&2; exit 1; }
  echo "Saving the old app's APK (versionCode $code)…"
  # cat rather than pull: on Android 7, adb pull can't stat the app's install folder ("does not exist").
  device exec-out cat "$apks" > "$dir/old-recorder.apk"
  unzip -tq "$dir/old-recorder.apk" >/dev/null || { echo "The saved APK isn't a valid zip." >&2; exit 1; }

  echo "Saving its private files…"
  # Everything but caches and the native-library link, which the install recreates.
  as_app find . -type f | grep -vE '^\./(cache|code_cache|lib)/' | while read -r file; do
    mkdir -p "$dir/internal/$(dirname "$file")"
    as_app cat "$file" > "$dir/internal/$file"
    echo "  ${file#./}"
  done

  echo "Saving its external files ($EXTERNAL)…"
  if device shell "[ -d $EXTERNAL ]"; then
    device pull "$EXTERNAL" "$dir/external" >/dev/null
  else
    mkdir -p "$dir/external"
  fi
  echo "$code" > "$dir/version-code"
  echo "Saved to $dir: $(find "$dir/internal" -type f | wc -l | tr -d ' ') private and $(find "$dir/external" -type f | wc -l | tr -d ' ') external file(s)."
}

restore() {
  [[ -f "$dir/old-recorder.apk" && -d "$dir/internal" && -d "$dir/external" ]] || { echo "$dir isn't a snapshot." >&2; exit 1; }
  local code
  code=$(installed_version_code)
  echo "This uninstalls $PKG${code:+ (versionCode $code)} from $serial, deleting all of its data, then restores the snapshot in $dir."
  read -r -p "Continue? [y/N] " answer
  [[ "$answer" == [yY] ]] || exit 1

  [[ -z "$code" ]] || device uninstall "$PKG" >/dev/null
  echo "Installing the old app…"
  # -t: Android Studio installs debug builds as test-only.
  device install -t "$dir/old-recorder.apk" >/dev/null

  echo "Putting back its private files…"
  device shell "rm -rf $STAGING && mkdir -p $STAGING"
  device push "$dir/internal/." "$STAGING/" >/dev/null
  device shell "chmod -R a+rX $STAGING"
  (cd "$dir/internal" && find . -type f ! -name .DS_Store) | while read -r file; do
    as_app mkdir -p "$(dirname "$file")"
    as_app cp "$STAGING/${file#./}" "$file"
    echo "  ${file#./}"
  done
  device shell "rm -rf $STAGING"

  echo "Putting back its external files…"
  device shell "rm -rf $EXTERNAL && mkdir -p $EXTERNAL"
  device push "$dir/external/." "$EXTERNAL/" >/dev/null
  # Finder's .DS_Store files would otherwise be files no take names in the old app's folders.
  device shell "find $EXTERNAL -name .DS_Store -exec rm -f {} \\;"

  echo "Restored $(cat "$dir/version-code" 2>/dev/null || echo 'the old app') from $dir. Open the old app once to check the projects are there."
}

case "$action" in
  backup) backup ;;
  restore) restore ;;
  *) usage ;;
esac
