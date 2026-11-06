# libs/flac — FLAC library (Java)

[Project Nayuki's FLAC library](https://www.nayuki.io/page/flac-library-java), copied from
[github.com/nayuki/FLAC-library-Java](https://github.com/nayuki/FLAC-library-Java) at commit
`e807ab25e28a3759ac82e119a406490f8e336b9b` (2022-09-12). It is pure Java, so the same code runs on
desktop and Android. Licensed LGPL-3.0-or-later: see `COPYING.txt` and `COPYING.LESSER.txt`.

Chapters published to WACS are encoded with it (`:shared`, `audio/flac/`).

## What was copied

The `common`, `decode` and `encode` packages, unchanged except as listed below. The `app` package
(command-line tools and a Swing player) was left out.

## Changes

Each is marked `[btt]` in the source.

1. **`encode/RiceEncoder.java`:** an assertion allowed Rice parameters up to 31, but escape codes
   reach 47. Assertions are on in Android debug builds, where some ordinary recordings crashed the
   encoder.
2. **`encode/LinearPredictiveEncoder.java`:** `StrictMath.log` instead of `Math.log`. `Math.log` can
   differ in the last bit between JVMs, which can change the encoded bytes. With `StrictMath` a
   recording encodes to the same bytes on desktop and Android, so republishing an unchanged chapter
   uploads nothing new.
3. **`encode/StreamFrameEncoder.java`** (new): encodes one frame at a time. `FlacEncoder` takes the
   whole recording as one array, which for a long chapter is more memory than Android allows.
4. **`common/FrameInfo.java`:** two switch expressions (Java 14) rewritten as classic switches, so
   the module compiles at Java 11 like the apps.

If you update from upstream, re-apply all four.
