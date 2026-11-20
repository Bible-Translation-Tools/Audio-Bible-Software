# Test fixtures

Small files for tests, UI walkthroughs, and trying features by hand (or by an agent) without
recording or downloading anything first.

## source-audio/

| File | What it is | Use it to |
|---|---|---|
| `en_ulb_gen_c1.orature` | The bundled English ULB source with source audio for **Genesis 1**: a tone per verse (31 verses, about 23 s) and a verse marker at the start of each. The same shape Orature's Source Audio export writes (MP3 + cue in `media/`, `media.yaml`). | Start an Oral Translation from English and get past Consume. Import it in Orature (Import, or Choose File on Consume). |

The audio is generated, not recorded, so it has no licence of its own. The text is the
English ULB (CC BY-SA 4.0), unchanged, which lets the import merge the audio into the installed
English ULB rather than install a second edition.

Regenerate:

```sh
REGENERATE_FIXTURES=1 ./gradlew :app-orature:desktopTest --tests '*GenerateSourceAudioFixtures*'
```

The generator (`app-orature/src/desktopTest/.../e2e/fixtures/SourceAudioFixtures.kt`) also
imports the result to check that it works.

Keep fixtures small: a chapter, not a book.
