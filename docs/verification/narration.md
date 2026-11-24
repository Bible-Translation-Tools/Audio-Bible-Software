# Narration: record a chapter

**Start from:** `fresh` (or Home in any state).
**Scripted as:** `OratureNarrationWalkthroughTest` (with screenshots).

## Steps
1. New Project (+) > Narration > source English > first version/edition offered > Genesis.
2. Chapter 1 opens. Press Record and read each line; press Next after each.
3. The last line's button reads Save. Press it.

## Expect, in the dump (`-Pbook=gen -Pchapter=1`)
- `mode` is `NARRATION`.
- `narration.lines` has `book`, `chapter`, then `v1` to `v31`, all `placed: true`.
- Every line has `audioSeconds` > 0 and `rms` > 0, at 330 Hz from the verify microphone.
- `startSeconds` increases line by line, and the last line ends near `narration.audioSeconds`.

## Must not
- Leave any line `placed: false`, or with zero audio, after Save.
