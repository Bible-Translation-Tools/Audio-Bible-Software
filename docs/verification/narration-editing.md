# Narration editing: re-record a verse, move a marker, undo

**Start from:** `narrated-gen1`. Open Genesis; chapter 1 opens on the narration screen.
**Scripted as:** `OratureNarrationEditingWalkthroughTest` (asserts everything below).

## Steps and expectations

### Re-record verse 2
1. Write `392` to `<data dir>/verify-mic-hz` (a different "voice").
2. Scroll to verse 2 ("The earth was without form"), press its Re-Record, wait about 1.5 s, press Save.
3. Delete `verify-mic-hz`.

Expect: `v2.pitchHz` about 392 and `v2.audioSeconds` about the new take's length.
Must not: change `v1` or `v3` (still about 330 Hz, same `audioSeconds`).

### Move a marker
4. In the waveform at the top, drag verse 3's marker (the line just left of its "3" label) to
   the right by about 45 px. The first ~18 px only start the drag; a marker cannot pass the
   next verse's.

Expect: `v3.startSeconds` increases (saved straight away, no Save needed).
Must not: change any line's `pitchHz` or `rms`; moving a marker does not change audio.

### Undo
5. Press Undo in the top bar.

Expect: `v3.startSeconds` is back to its value before the drag.

## Known gaps
- Markers have no accessibility labels, so they are found by the verse number drawn on their
  chip. Screen readers cannot reach them either.
- Undo is enabled right after narrating, so "Undo became enabled" proves nothing; compare
  `startSeconds`.
