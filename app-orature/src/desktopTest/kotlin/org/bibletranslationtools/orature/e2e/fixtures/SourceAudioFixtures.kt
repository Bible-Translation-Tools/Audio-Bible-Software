package org.bibletranslationtools.orature.e2e.fixtures

import java.io.File
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue
import org.bibletranslationtools.orature.e2e.harness.OratureUiTestHarness
import org.bibletranslationtools.otter.common.domain.audio.AudioExporter
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.bibletranslationtools.otter.common.domain.project.ImportProjectUseCase
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import org.koin.core.context.GlobalContext
import org.wycliffeassociates.resourcecontainer.ResourceContainer
import org.wycliffeassociates.resourcecontainer.entity.Media
import org.wycliffeassociates.resourcecontainer.entity.MediaManifest
import org.wycliffeassociates.resourcecontainer.entity.MediaProject

/**
 * Audio files checked in under `test-fixtures/` at the repository root, for UI journeys and
 * for anyone (or any agent) who needs real source audio to try a feature by hand.
 */
object SourceAudioFixtures {

    /** Tests run with the module directory (app-orature) as the working directory. */
    private val repoRoot: File = File("..").canonicalFile

    /**
     * The bundled English ULB source with source audio for Genesis 1 added, as a `.orature`
     * file: what Orature's Source Audio export produces. Importing it gives Genesis 1 source
     * audio, which translation projects need before they can get past Consume.
     *
     * The audio is a tone per verse (31 verses, about 23 seconds) with a verse marker at the
     * start of each, so the waveform shows the verse breaks. Regenerate with
     * `REGENERATE_FIXTURES=1 ./gradlew :app-orature:desktopTest --tests '*GenerateSourceAudioFixtures*'`.
     */
    val englishGenesis1: File = repoRoot.resolve("test-fixtures/source-audio/en_ulb_gen_c1.orature")

    /** Imports [file] the way Import (or Choose File on Consume) does. Needs the harness running. */
    fun import(file: File) {
        check(file.exists()) { "Missing fixture ${file.path}; see SourceAudioFixtures" }
        val result = GlobalContext.get().get<ImportProjectUseCase>().import(file).blockingGet()
        check(result == ImportResult.SUCCESS) { "Importing ${file.name} failed: $result" }
    }

    internal const val GENESIS_1_VERSES = 31
    internal val bundledEnglishUlb: File =
        repoRoot.resolve("shared/src/commonMain/composeResources/files/content/en_ulb.zip")
}

/**
 * Rebuilds the files in `test-fixtures/source-audio/`. Does nothing unless REGENERATE_FIXTURES
 * is set, so normal test runs never rewrite checked-in files.
 */
class GenerateSourceAudioFixturesTest {

    @Test
    fun englishGenesis1() {
        if (System.getenv("REGENERATE_FIXTURES") == null) return
        OratureUiTestHarness.start()
        try {
            val out = SourceAudioFixtures.englishGenesis1
            out.parentFile.mkdirs()
            val work = kotlin.io.path.createTempDirectory("fixture").toFile()

            // 1. A WAV with a short tone per verse and a verse marker where each starts.
            val wav = work.resolve("en_ulb_gen_c1.wav")
            val audio = OratureAudioFile(wav, 1, SAMPLE_RATE, 16)
            audio.writer().use { stream ->
                var frame = 0
                for (verse in 1..SourceAudioFixtures.GENESIS_1_VERSES) {
                    audio.addVerseMarker(frame, "$verse")
                    val pitch = TONES[(verse - 1) % TONES.size]
                    val bytes = tone(pitch, VERSE_SECONDS) + silence(GAP_SECONDS)
                    stream.write(bytes)
                    frame += bytes.size / 2
                }
            }
            audio.update()

            // 2. MP3 + cue, exactly as Source Audio export writes them.
            val mp3 = work.resolve("en_ulb_gen_c1.mp3")
            val cues = OratureAudioFile(wav).getCues()
            GlobalContext.get().get<AudioExporter>()
                .exportMp3(wav, mp3, AudioExporter.ExportMetadata(license = null, markers = cues))
                .blockingAwait()
            val cue = work.resolve("en_ulb_gen_c1.cue")

            // 3. The bundled English ULB (unchanged text, so it merges into the installed one)
            //    with a media manifest and the chapter audio added.
            val zip = work.resolve("en_ulb.zip")
            SourceAudioFixtures.bundledEnglishUlb.copyTo(zip)
            ResourceContainer.load(zip).use { rc ->
                rc.media = MediaManifest().apply {
                    projects = listOf(
                        MediaProject(identifier = "gen").apply {
                            media = listOf("mp3", "cue").map { type ->
                                Media(
                                    identifier = type,
                                    version = "",
                                    url = "",
                                    quality = listOf(),
                                    chapterUrl = "media/en_ulb_gen_c{chapter}.$type"
                                )
                            }
                        }
                    )
                }
                rc.write()
                listOf(mp3, cue).filter { it.exists() }.forEach { file ->
                    rc.accessor.write("media/${file.name}") { os -> file.inputStream().use { it.copyTo(os) } }
                }
            }
            zip.copyTo(out, overwrite = true)
            work.deleteRecursively()

            // 4. Check it does what it is for: importing it gives Genesis 1 source audio.
            OratureUiTestHarness.stop()
            OratureUiTestHarness.start()
            SourceAudioFixtures.import(out)
            assertTrue(out.length() > 0)
        } finally {
            OratureUiTestHarness.stop()
        }
    }

    private fun tone(hz: Double, seconds: Double): ByteArray {
        val frames = (SAMPLE_RATE * seconds).toInt()
        val fade = SAMPLE_RATE / 50 // 20 ms, so verses don't click
        val bytes = ByteArray(frames * 2)
        for (i in 0 until frames) {
            val envelope = min(1.0, min(i, frames - i).toDouble() / fade)
            val sample = (sin(2 * PI * hz * i / SAMPLE_RATE) * 0.4 * envelope * Short.MAX_VALUE).toInt()
            bytes[2 * i] = (sample and 0xff).toByte()
            bytes[2 * i + 1] = (sample shr 8 and 0xff).toByte()
        }
        return bytes
    }

    private fun silence(seconds: Double) = ByteArray((SAMPLE_RATE * seconds).toInt() * 2)

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val VERSE_SECONDS = 0.6
        const val GAP_SECONDS = 0.15
        val TONES = listOf(330.0, 392.0, 440.0)
    }
}
