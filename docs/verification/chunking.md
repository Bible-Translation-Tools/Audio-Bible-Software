# Oral translation: Consume and Chunking

**Start from:** `translation-gen1` (Genesis 1 open on Consume).
**Scripted as:** `OratureChunkingWalkthroughTest`.

## Steps
1. Press Play Source; the waveform shows a mark at each verse. Pause.
2. Steps (left) > Chunking.
3. Three times: play, stop after about 3 s, press Add Chunk.
4. Try to go on to Blind Draft.

## Expect (correct behaviour)
- After leaving Chunking, the project's `chunks` in the dump lists the chunks you made.
- Blind Draft becomes reachable.

## Today on main: fails (known bug)
- `chunks` stays `null`, and Blind Draft stays locked. Chunks are saved only when leaving
  Chunking forward, and forward is Blind Draft, which unlocks only once chunks are saved
  (`computeReachableStep`, `commitStepNavigation`, `OratureChunkingViewModel.onCleared`).
- The scripted test asserts this broken behaviour on purpose and fails, with a message saying
  so, as soon as chunks are saved: that is the signal to extend it into drafting.
