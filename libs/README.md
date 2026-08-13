# libs/ — vendored WA libraries

Source for the Wycliffe Associates libraries the backend depends on, previously consumed as
published artifacts from `nexus-registry.walink.org`. They live here so they can be changed in
step with the apps rather than through a publish cycle.

| Module | Package | Was |
|---|---|---|
| `:libs:resource-container` | `org.wycliffeassociates.resourcecontainer` | `org.wycliffeassociates:kotlin-resource-container:0.12.0` |
| `:libs:scripture-burrito` | `org.bibletranslationtools.scriptureburrito` | `org.bibletranslationtools:kotlin-scripture-burrito:1.0.1` |
| `:libs:tstudio2rc` | `org.wycliffeassociates.tstudio2rc` | `org.wycliffeassociates:kotlin-tstudio2rc:1.0.2` |
| `:libs:scripture-alignment` | `org.bibletranslationtools.kotlinscripturealignment` | `org.bibletranslationtools:kotlin-scripture-alignment:1.0.0` |
| `:libs:vtt` | `org.bibletranslationtools.vtt` | `org.bibletranslationtools:kotlin-vtt:1.0.0` |

Internal graph — `tstudio2rc` calls `resource-container`, `scripture-alignment` calls `vtt`, and
the other two are leaves. **Order does not matter.** Sibling dependencies are declared by
coordinate, not as `project(...)`, so substitution resolves them whichever way round you vendor
them: a module that isn't in yet keeps resolving to its published jar.

## Current state

All five modules hold source and build green, with their own tests passing. Only two are switched
on, though:

| Module | Substitution | Why |
|---|---|---|
| `resource-container` | **on** | in use by `:shared` |
| `tstudio2rc` | **on** | in use by `:shared` |
| `scripture-burrito` | off | API drift — see below |
| `scripture-alignment` | off | API drift — see below |
| `vtt` | off | published `scripture-alignment` 1.0.0 needs the published vtt |

The vendored sources are NEWER than the artifacts they replace — `scripture-burrito` is 1.0.2
against a published 1.0.1, `scripture-alignment` is 1.3.2-demo against 1.0.0 — and the newer
versions changed their APIs. Switching those two on produces 15 compile errors across three
`:shared` files, which need decisions rather than mechanical edits:

- `BurritoAudioAlignment` moved to a `.model` subpackage (mechanical), but `getVttCues()` and
  `setRecordsFromVttCueContent()` also gained a leading `docid` parameter. The library matches a
  docid against either the stored value or its basename, so `audioFile.name` — which
  `BurritoAlignmentMetadata` already holds — is the likely answer, but picking wrong writes
  wrong timings rather than failing loudly.
- `AudioFlavorSchema` was redesigned: the `performance` + `formats` constructor is gone, replaced
  by a no-arg constructor with `conventions: AudioConventions?`. `:shared` currently writes
  `Performance.READING/SINGLE_VOICE` and `format-wav`/`format-mp3` entries into exported burrito
  metadata, and there is no mechanical translation of that into the new shape.
- `getFormats()` / `compression` in `BurritoToResourceContainerConverter` are the read side of
  the same schema change.

Until those are resolved, the two stay off and `:shared` keeps compiling against the published
1.0.1/1.0.0 jars.

## Why plain `kotlin("jvm")` and not KMP

`:shared` is a KMP module, but both of its targets (`androidTarget`, `jvm("desktop")`) are JVM, so
a plain JVM module resolves cleanly from `commonMain`. This was verified against the real build,
not assumed. None of these libraries contains platform-specific code — notably, all four already
use `java.util.zip` rather than `java.nio.file.FileSystems`, so none of them needs the
Android/desktop split that forced `AndroidZipFileWriter` to exist in `:shared`.

If one of them ever does need per-platform behaviour, converting it to KMP means adding
`kotlinMultiplatform` + `androidLibrary` plugins and moving `src/main/kotlin` to
`src/commonMain/kotlin`. Nothing else about the wiring changes.

## Dropping code in

1. Copy the library's sources into `libs/<module>/src/main/kotlin/` and its tests into
   `libs/<module>/src/test/kotlin/`.
2. **Keep the original package names.** Every module's build file is already set up for them, and
   `:shared` has ~19 files importing these packages. Renaming is a separate, larger change.
3. Uncomment that library's line in the `dependencySubstitution` block in the root
   [build.gradle.kts](../build.gradle.kts). Nothing in `:shared/build.gradle.kts` needs to change —
   substitution redirects both the direct dependency and any transitive reference to the same
   coordinates.
4. Build: `./gradlew :shared:compileDebugKotlinAndroid :shared:desktopTest`

Do them one at a time; the build stays green between each.

### Declared dependencies

These libraries were published **without dependency metadata** — three of the four are leaf nodes
in the resolved graph despite genuinely using Jackson and Tika, which is why `:shared` had to
declare `tika-core` on their behalf. The dependencies in each module's build file were therefore
recovered by reading the published bytecode, not the POMs. If a drop-in fails to compile with an
unresolved import, the cause is most likely a dependency that was never declared anywhere; add it
to that module and note it here.

## Two migrations these modules exist to enable

**Remove Tika.** Done in `resource-container` — `ResourceContainer.isZipFile()` now reads the
four-byte local file header and the module declares no tika-core. `scripture-burrito` still has
its copy and still needs the same treatment; until then `:shared` keeps the repackaged
`tikaCoreForAndroid` jar for it. Note the two cannot both declare tika-core while `:shared` also
supplies the repackaged one — that combination fails `checkDuplicateClasses`. The replacement:

```kotlin
private fun File.isZip(): Boolean {
    if (!isFile) return false
    return try {
        inputStream().use { stream ->
            val header = ByteArray(4)
            stream.read(header) == 4 &&
                header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte() &&
                header[2].toInt() == 3 && header[3].toInt() == 4
        }
    } catch (e: IOException) {
        false
    }
}
```

That drops a 397-class dependency and lets the `tikaCoreForAndroid` repackaging task in
`shared/build.gradle.kts` be deleted along with it. (`PK\x03\x04` is a non-empty archive; empty
ones start `PK\x05\x06`, which no resource container will be.)

**Remove Jackson.** All of them except `vtt` use `ObjectMapper`, and jackson-databind calls
`Constructor.getParameterCount()` — API 26 — from its core deserializer path, which is what
currently breaks both apps on Android 7. Once these are in-tree, the last genuinely third-party
Jackson consumer is Retrofit's `converter-jackson` at a single call site, so Jackson can be
removed outright in favour of kotlinx-serialization. The one piece with no drop-in equivalent is
YAML (`jackson-dataformat-yaml`), used for RC manifests and Orature's plugin registry;
`com.charleskorn.kaml` is the candidate.
