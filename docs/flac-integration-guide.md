# Handoff: integrating deterministic, low-memory FLAC encode/decode into an app

> Provided by the product owner (2026-09) as the reference for WACS milestone **M5(b) – FLAC masters**.
> It describes integrating a separate working reference repo (with `flac-lib/`, `shared/jvmSharedMain/`,
> etc.) *into* an app like BTT-Recorder2. Paths like `flac-lib/` and `com/example/flactest/` are the
> reference repo's, to be vendored/copied here.

Audience: an engineer or coding agent adding **WAV → FLAC encoding (and lossless verification)** to
a Kotlin/JVM and/or Android app. This repo is a working reference implementation; this doc tells you
what to reuse, how to wire it up, and the non-obvious constraints that will bite you if ignored.

---

## 1. What you're getting

A pure-JVM (no native code) FLAC **encoder + decoder** that:

- Runs identically on **desktop JVM and Android** (same bytecode).
- Is **deterministic**: the same PCM input always yields **byte-identical** FLAC output — same run to
  run, and *even across platforms* (Android output == desktop output, bit for bit).
- Has a **constant-memory (streaming) path** for both encode and decode, so multi-minute files work
  on Android **without `largeHeap`**.

The FLAC engine is **Project Nayuki's "FLAC library (Java)"** (LGPL-3.0-or-later), vendored in
`flac-lib/` with 4 small marked (`[flac-test]`) changes. All the streaming/verification logic is
plain Kotlin in `shared/`.

> Not on Maven Central. You vendor the library (copy `flac-lib/`) — that's expected and fine (LGPL).

---

## 2. The pieces you actually reuse

Copy these into your app. Everything below is JVM-only code (uses `java.*`), so in a Kotlin
Multiplatform project it must live in an Android+JVM–shared source set, **not** `commonMain`.

### From `flac-lib/` (Java, vendored — copy the whole module)
`src/main/java/io/nayuki/flac/{common,decode,encode}/**`. Do **not** copy `app/` (Swing/`javax.sound`).
Includes our added public shim `encode/StreamFrameEncoder.java`.

### From `shared/src/jvmSharedMain/kotlin/com/example/flactest/` (Kotlin)
| File | What it gives you |
|------|-------------------|
| `StreamingFlacEncoder.kt` | **Constant-memory encoder.** `encode(InputStream, File)`, `encodeAndSha256(InputStream, File): String`, `sha256File(File): String` |
| `StreamingFlacDecoder.kt` | **Constant-memory lossless check.** `verify(File): DecodeVerification` (`.lossless`, `.computedMd5`, `.embeddedMd5`) |
| `FlacCodec.kt` | Whole-file convenience: `readWav(ByteArray): WavAudio`, `encodeToFlacFile(WavAudio, File)`, `decodeFlacFile(File): WavAudio`, `encodeToFlacBytes`, `decodeFlacBytes` |
| `WavAudio.kt` | PCM container (`samples: Array<IntArray>`, `sampleRate`, `sampleDepth`) |
| `WavGenerator.kt` | Test-only: deterministic in-memory sine WAV |

Optional (only if you want the batch/determinism harness, not needed for a normal "convert" feature):
`StreamingBatchVerifier.kt`, `BatchVerifier.kt`, `FlacDeterminismVerifier.kt`, and the report models
in `commonMain` (`VerificationResult.kt`, `BatchReport.kt`, plus `StreamingReport` inside the batch file).

**For a real app, you almost certainly only need `StreamingFlacEncoder` + `StreamingFlacDecoder`.**

---

## 3. Minimal integration

### Gradle
Add the vendored library as a module dependency from your Android+JVM shared source set:
```kotlin
// shared/build.gradle.kts (an intermediate source set shared by androidTarget + jvm)
val jvmSharedMain by creating {
    dependsOn(commonMain)
    dependencies { implementation(project(":flac-lib")) }
}
val jvmMain by getting { dependsOn(jvmSharedMain) }
val androidMain by getting { dependsOn(jvmSharedMain) }
```
`flac-lib` is a plain `java-library` compiled to Java 17 bytecode. (If your app is Android-only or
JVM-only, just drop the Kotlin files + `flac-lib` sources into that one module — no KMP needed.)

### Encode a WAV to FLAC (low memory, works for any file size)
```kotlin
// Android: `input` = contentResolver.openInputStream(uri) or assets.open(name)
// Desktop: `input` = file.inputStream()
val outFlac = File(cacheDir, "out.flac")          // must be a real, seekable File
StreamingFlacEncoder.encode(input, outFlac)        // constant memory, deterministic
```

### Confirm it's lossless (low memory)
```kotlin
val v = StreamingFlacDecoder.verify(outFlac)
check(v.lossless) { "FLAC did not decode back to the original PCM" }
```

### Get the checksum (e.g. to dedupe or record provenance)
```kotlin
val sha = StreamingFlacEncoder.sha256File(outFlac)   // or encodeAndSha256(input, outFlac)
```

That's the whole feature. No `largeHeap`, no native libs.

---

## 4. Hard constraints — read these before coding

1. **Input must be canonical PCM WAV.** Uncompressed PCM (format `0x0001`), sample depth **8/16/24/32
   bits only** (whole bytes), **1–8 channels**, sample rate `< 2^20`. Float WAV, ADPCM, and non-byte
   depths (12/20-bit) are **rejected** — this is a Nayuki limitation, not just ours. If you must accept
   arbitrary audio, transcode to PCM WAV first (e.g. ffmpeg) before calling the encoder.

2. **Encoding needs a seekable output `File`.** The encoder rewrites STREAMINFO at the end (`seek(4)`),
   so you cannot encode to a pure `OutputStream`/socket. Write to a temp `File` (on Android use
   `cacheDir`), then upload/move it. Input, by contrast, *is* a stream.

3. **Assertions bug fix is required for Android debug builds.** Android **debug** builds enable Java
   `assert`s (desktop `java`/Gradle runs don't). The vendored `RiceEncoder.java` has our
   `[flac-test]` fix (`param <= 31` → `param <= 47`); **without it, some valid inputs crash with
   `AssertionError` on Android debug**. If you re-vendor from upstream, re-apply that fix.

4. **Determinism depends on `StrictMath`.** The vendored `LinearPredictiveEncoder.java` uses
   `StrictMath.log` (our `[flac-test]` change) instead of `Math.log`. This is what makes
   Android-vs-desktop output *bit-identical*. Keep it. (Everything else is integer/`+−×÷`, already
   bit-exact.)

5. **Checksums are encoder-specific.** A FLAC checksum identifies "*this* encoder + settings produced
   *this* file", not "this audio". Our output is **not** equal to ffmpeg's (or any other encoder's) —
   both are lossless, but the container bytes differ. If you ever swap encoders or settings, every
   checksum changes. The encoder-independent identity of the audio is the **decoded PCM MD5** (which
   FLAC stores in STREAMINFO — `StreamingFlacDecoder.verify().computedMd5`).

6. **Temp files.** Encode/whole-file-decode use `File.createTempFile`; on Android pass a `tmpDir`
   (cacheDir) where you control cleanup. Delete them yourself for large files.

---

## 5. Memory model (why streaming matters)

- **Whole-file path** (`FlacCodec` / stock `FlacEncoder`): holds all samples as `int[channels][n]` —
  ~`4 × totalSamples × channels` bytes. A 90 MB mono WAV ≈ **180 MB** heap just for the array. This
  is what forced `largeHeap` and still OOM'd a constrained device.
- **Streaming path** (`StreamingFlacEncoder`/`Decoder`): one 4096-sample block at a time → **tens of
  KB**, independent of file length. Verified: encodes+decodes a 90 MB file under **`-Xmx32m`** on
  desktop and on a **stock 48 MB-heap Android API 24** device.

**Recommendation:** use the streaming path in any app. Only use the whole-file `FlacCodec` for small
clips or when you already have the samples in memory.

---

## 6. Verified facts (so you can trust the above / re-check)

- Encoder is deterministic: 5 runs → identical SHA-256 (per platform).
- Cross-platform identical: Android API 24 output == desktop output, byte-for-byte (10 real + synthetic
  files; manifest `ba08d9d4…` matched on both).
- Streaming output == whole-file output (byte-identical) — unit test `StreamingEncoderTest`.
- Round-trip lossless verified by decoded-PCM-MD5 vs embedded STREAMINFO MD5.

Re-run yourself:
```bash
# builds/tests need JDK 17–21 (NOT 25 — AGP 8.7 won't run on it); Android SDK 35 + build-tools 35 for the APK
JAVA_HOME=/path/to/jdk-21 ./gradlew :shared:jvmTest
JAVA_HOME=/path/to/jdk-21 ./gradlew :desktopApp:run --args="stream"      # STREAMING | WHOLE-FILE | LOSSLESS table
```
Toolchain used there: Kotlin 2.1.0, AGP 8.7.3, Gradle 8.11.1, compileSdk 35, minSdk 24, Java 17 bytecode.

---

## 7. Known limitations / out of scope

- **No 12/20-bit or non-PCM input** (see constraint #1). Ref: Nayuki
  [issue #7](https://github.com/nayuki/FLAC-library-Java/issues/7) (decoder also fails 12/20-bit and
  missing-sample-count FLACs). Not relevant if you only encode from standard WAV; the streaming
  decoder happens to sidestep the missing-sample-count case, but odd depths are unsupported.
- **No metadata** (tags, album art, seektable, cue). Output is bare STREAMINFO + audio frames. Add a
  VORBIS_COMMENT/PADDING block writer if you need tags.
- **Not real-time/pull-based.** Encoder consumes an `InputStream` to completion and writes a `File`.
- **Alternatives considered:** `jflac` — decoder only (its `FLACEncoder` is an unimplemented stub);
  `ffmpeg` (bytedeco JNI or CLI) — streaming + fast, but different native builds mean Android==desktop
  byte-identical output is *not* guaranteed. See `EXPERIMENTS.md`. If you don't need cross-platform
  identical bytes and want max speed/format coverage, bytedeco ffmpeg is the pragmatic choice; if you
  need the deterministic guarantee in pure JVM, use this.

---

## 8. Licensing

`flac-lib/` is **LGPL-3.0-or-later** (Project Nayuki). Keep `COPYING.txt` / `COPYING.LESSER.txt` and
the per-file headers. Our modifications are marked `[flac-test]` in-file and listed in `README.md`.
LGPL is generally fine for app inclusion, but confirm against your distribution model. Consider
upstreaming the `RiceEncoder` assert fix as a PR to Nayuki (it's a genuine upstream bug).

---

## 9. Map of the reference repo

```
flac-lib/     Vendored Nayuki FLAC (Java) + StreamFrameEncoder shim   ← copy this
shared/       KMP module (jvm + androidTarget)
  commonMain/     pure-Kotlin report models
  jvmSharedMain/  the codec + streaming encoder/decoder               ← copy these
  jvmTest/        equality + lossless tests
desktopApp/   JVM CLI: `run` (batch verify), `run --args="stream"` (streaming demo)
androidApp/   Plain-Activity app (no AndroidX); streaming encode+decode; no largeHeap
sample-wavs/  Drop-in WAV folder (desktop reads it; Android bundles it as assets)
scripts/ffmpeg-determinism.sh   CLI comparison (optional)
README.md, EXPERIMENTS.md       Deeper detail
```

Start by reading `StreamingFlacEncoder.kt` and `StreamingFlacDecoder.kt` — they're small and are the
whole story.

---

## 10. How this maps onto BTT-Recorder2 (M5b notes)

- **Source-set placement:** this repo's `:shared` targets `androidTarget` + `jvm("desktop")` and uses
  JVM APIs (`java.io.File`) in `commonMain` today, but the FLAC code and `flac-lib` are strictly
  JVM-only — safest to add an intermediate `jvmSharedMain` (dependsOn commonMain; both `androidMain`
  and `desktopMain` dependsOn it) as the guide shows, and vendor `:flac-lib` as a Gradle module.
  Verify `:shared:checkAndroidApiLevel` still passes (Java-17 bytecode dexed for minSdk 24 — confirm
  no unsupported APIs, same diligence as the JGit decision).
- **Where it plugs in:** the publish path (`PublishChapterToWacs`) currently takes the selected take's
  WAV directly (`take.file`, `extension = "wav"`). For FLAC masters, convert WAV→FLAC into a staging
  file first, then pointer/oid/upload the `.flac`; the `.gitattributes`/`WacsRepoLayout` already track
  `*.flac`. Keep WAV as a fallback/config.
- **Checksum caution (constraint #5):** our LFS dedup/oid is the sha256 of the *file bytes*. FLAC
  bytes are encoder-specific, so a WAV and its FLAC have different oids (expected — they're different
  ingredients). The *audio identity* across encoders is the decoded-PCM MD5 in STREAMINFO if we ever
  need encoder-independent comparison.
