# Verifying Orature

How to check that an Orature feature works, by hand or as an agent, with evidence better than
a screenshot. Everything here lives in test code (`app-orature/src/desktopTest/.../verify` and
`.../e2e`) and `scripts/verify/`; none of it ships in the app.

## The kit

| Piece | What it gives you | How |
|---|---|---|
| **Starting states** | A complete Orature data folder already at the interesting point, so a check starts at the feature, not in the project wizard. | `./gradlew :app-orature:seedVerifyStates` (all, about 4 min) or `-PverifyState=<name>`. Built in `app-orature/build/verify-states/<name>/`. |
| **The verify app** | The real Orature window on a *copy* of a state, with a known microphone and silent speakers. Never touches your own projects. | `./gradlew :app-orature:runVerifyApp -PverifyState=<name>`. Prints `VERIFY_DATA_DIR=<path>`. `-PdataDir=<path>` reopens an earlier run. |
| **The state dump** | What Orature actually saved, as JSON: project mode, chunks, and for each narrated line where it starts, how long it is, its pitch and loudness. Decide from this, not from the screen. | `./gradlew -q :app-orature:dumpState -PdataDir=<path> [-Pbook=gen] [-Pchapter=1]` |
| **The verify microphone** | A 330 Hz tone instead of a voice, so a recording can be checked without listening. Write another pitch to `<data dir>/verify-mic-hz` (for example `392`) to record "in a different voice"; delete it to go back. | Used by the verify app and every UI test. |
| **Source audio fixture** | Genesis 1 source audio (a tone per verse, with verse markers), for translation projects. | `test-fixtures/source-audio/`; the states already include it. |
| **Android** | The same dump for a device or emulator. | `scripts/verify/pull-android-state.sh <out-dir>`, then `dumpState -PdataDir=<out-dir>`. Debug builds only. |

### States

| Name | What is there |
|---|---|
| `fresh` | First launch done, Genesis 1 source audio imported, no projects. |
| `narrated-gen1` | Narration project, English Genesis, chapter 1 narrated: every line about 1 s of 330 Hz. |
| `translation-gen1` | Oral Translation project, English to Spanish, Genesis 1 open on Consume. |

## A check, start to finish

1. Pick the spec for the feature below, and its starting state.
2. `./gradlew :app-orature:runVerifyApp -PverifyState=<state>`; note `VERIFY_DATA_DIR`.
3. Dump once before you start: `./gradlew -q :app-orature:dumpState -PdataDir=<dir> > before.json`.
4. Do the steps in the spec in the window (or, on Android, on the emulator).
5. Dump again and compare with what the spec says should change, and what must not.
6. Report: the steps you did, the two dumps (or the relevant lines), and pass/fail per expectation.

On Android: install a debug build (`./gradlew :app-orature:assembleDebug`), import
`test-fixtures/source-audio/en_ulb_gen_c1.orature` (push it with `adb push` to
`/sdcard/Download/`, then Import in the app), do the steps, then pull and dump. The emulator's
microphone records silence unless host audio is enabled, so on Android check lengths and
`rms`, not pitch.

## Specs

- [Narration: record a chapter](narration.md)
- [Narration editing: re-record a verse, move a marker, undo](narration-editing.md)
- [Oral translation: Consume and Chunking](chunking.md)

Each spec is also a scripted UI test (`app-orature/src/desktopTest/.../e2e`) that checks the
same expectations against the same dump, so a regression fails CI without anyone driving:
`./gradlew :app-orature:desktopTest --tests '*e2e.Orature*'`.

## Reading the dump

```json
{ "line": "v2", "placed": true, "startSeconds": 2.69, "audioSeconds": 1.49, "pieces": 1, "pitchHz": 392, "rms": 0.212 }
```

- `line`: `book`, `chapter`, `v1`, `v2`, ... (or `v4-5` for a bridge).
- `startSeconds`: where the line's marker sits in the chapter. Moving a marker changes this.
- `audioSeconds`, `pieces`: how much audio the line has and in how many stretches of the
  working file. Re-recording replaces a line's audio; it may land in a new stretch.
- `pitchHz`: from the verify microphone, the tone it was recorded with (within about 10 Hz;
  the first few milliseconds of a new take can still be the old tone). Meaningless for speech.
- `rms`: loudness, 0 to 1. 0 is silence. The verify microphone's tone is about 0.21.
- `chunks` (per project): the saved `chunks.json`, or `null` when no chunks are saved.

The dump reads files only (each project's `.apps/orature/`), never the database, so it can
read a folder while the app is open, and a folder copied off a device.
