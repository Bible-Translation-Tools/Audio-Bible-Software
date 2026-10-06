package org.bibletranslationtools.bttrecorder2.legacymigration

import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.bttrecorder2.legacymigration.CompileLegacyChapterTake.Result
import org.bibletranslationtools.otter.common.api.persistence.ITempFileProvider
import org.bibletranslationtools.otter.common.data.audio.VerseMarker
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.bibletranslationtools.otter.common.domain.content.ConcatenateAudio
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CompileLegacyChapterTakeTest {

    private val root: File = Files.createTempDirectory("compile-legacy").toFile()
    private val tempFiles = object : ITempFileProvider {
        override val tempDirectory: File = root.resolve("tmp").apply { mkdirs() }
        override fun createTempFile(prefix: String, suffix: String?): File = File.createTempFile(prefix, suffix, tempDirectory)
        override fun cleanTempDirectory() = Unit
    }
    private val compile = CompileLegacyChapterTake(ConcatenateAudio(tempFiles), tempFiles)

    @AfterTest
    fun clean() {
        root.deleteRecursively()
    }

    // ── joining ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `takes are joined in verse order, with a marker where each verse or chunk starts`() {
        val first = take(1..3, frames = 1000)
        val second = take(4..5, frames = 600)

        val result = compiled(plan(first, second))

        assertEquals(1600, result.totalFrames)
        val expected = listOf(VerseMarker(1, 3, 0), VerseMarker(4, 5, 1000))
        assertEquals(expected, result.markers)
        assertEquals(expected, markersIn(result.file), "the file holds exactly these markers")
        assertTrue(result.problems.isEmpty(), "${result.problems}")
    }

    @Test
    fun `the audio is the takes' audio, unchanged, in order`() {
        val first = take(1..3, frames = 1000)
        val second = take(4..5, frames = 600)

        val result = compiled(plan(first, second))

        assertContentEquals(LegacyTakeWav.pcm(1000, first.id) + LegacyTakeWav.pcm(600, second.id), pcmOf(result.file))
    }

    @Test
    fun `the old app's numbered cues and metadata don't come along`() {
        // Each take carries a bare-numbered cue and the old app's LIST/INFO JSON.
        val result = compiled(plan(take(1..3, frames = 500), take(4..5, frames = 500)))

        assertTrue(markersIn(result.file).all { it is VerseMarker }, "${markersIn(result.file)}")
        assertEquals(2, markersIn(result.file).size)
    }

    @Test
    fun `the same plan always gives the same bytes`() {
        val plan = plan(take(1..3, frames = 700, inner = mapOf("2" to 300)), take(4..5, frames = 400))

        val once = compiled(plan, root.resolve("out/once.wav")).file.readBytes()
        val twice = compiled(plan, root.resolve("out/twice.wav")).file.readBytes()

        assertContentEquals(once, twice)
    }

    // ── inner markers ──────────────────────────────────────────────────────────────────────

    @Test
    fun `the recordist's inner verse markers are carried at their offsets`() {
        val first = take(1..3, frames = 1000)
        val second = take(4..6, frames = 900, inner = mapOf("5" to 300, "6" to 600))

        val result = compiled(plan(first, second))

        assertEquals(
            listOf(VerseMarker(1, 3, 0), VerseMarker(4, 6, 1000), VerseMarker(5, 5, 1300), VerseMarker(6, 6, 1600)),
            result.markers
        )
        assertEquals(result.markers, markersIn(result.file))
    }

    @Test
    fun `inner markers that don't fit are set aside and reported, and the audio is kept`() {
        val take = take(
            4..6, frames = 900,
            // verse 4 at 0 is the take's own start; 9 is outside it; 5 at 5000 is past its end; "x" isn't a verse
            inner = mapOf("4" to 0, "9" to 100, "5" to 5000, "x" to 10, "6" to 450)
        )

        val result = compiled(plan(take))

        assertEquals(listOf(VerseMarker(4, 6, 0), VerseMarker(6, 6, 450)), result.markers)
        val setAside = assertIs<MigrationProblem.InnerMarkersSetAside>(result.problems.single())
        assertEquals(setOf("9", "5", "x"), setAside.markers.map { it.label }.toSet())
        assertFalse(setAside.blocksDeletion)
        assertEquals(900, result.totalFrames)
    }

    // ── repairing and refusing ─────────────────────────────────────────────────────────────

    @Test
    fun `a take whose recording never finished is repaired, and the old file is left as it was`() {
        val first = take(1..3, frames = 800)
        val interrupted = take(4..5, frames = 500, declaredAudioBytes = 0, oldAppMetadata = false)
        val before = interrupted.file.readBytes()

        val result = compiled(plan(first, interrupted))

        assertEquals(1300, result.totalFrames)
        assertEquals(listOf(VerseMarker(1, 3, 0), VerseMarker(4, 5, 800)), result.markers)
        assertContentEquals(LegacyTakeWav.pcm(800, first.id) + LegacyTakeWav.pcm(500, interrupted.id), pcmOf(result.file))
        val repaired = assertIs<MigrationProblem.AudioRepaired>(result.problems.single())
        assertFalse(repaired.blocksDeletion)
        assertContentEquals(before, interrupted.file.readBytes(), "the old take is only read")
    }

    @Test
    fun `a take declaring more audio than it holds is repaired to what it holds`() {
        val truncated = take(1..3, frames = 400, declaredAudioBytes = 400L * 2 + 1000, oldAppMetadata = false)

        val result = compiled(plan(truncated))

        assertEquals(400, result.totalFrames)
        assertIs<MigrationProblem.AudioRepaired>(result.problems.single())
    }

    @Test
    fun `a take that isn't audio is left out and reported, and the rest is joined`() {
        val broken = take(1..3, frames = 10).also { it.file.writeText("not a recording") }
        val good = take(4..5, frames = 600)

        val result = compiled(plan(broken, good))

        assertEquals(listOf(VerseMarker(4, 5, 0)), result.markers)
        val problem = assertIs<MigrationProblem.AudioUnreadable>(result.problems.single())
        assertTrue(problem.blocksDeletion)
    }

    @Test
    fun `a take that isn't 44_1 kHz 16-bit mono is left out and reported`() {
        val stereo = take(1..3, frames = 300, channels = 2)
        val otherRate = take(4..5, frames = 300, sampleRate = 48000)
        val good = take(6..8, frames = 300)

        val result = compiled(plan(stereo, otherRate, good))

        assertEquals(listOf(VerseMarker(6, 8, 0)), result.markers)
        assertEquals(2, result.problems.count { it is MigrationProblem.AudioFormatUnexpected })
    }

    @Test
    fun `when no take can be used, no chapter take is written`() {
        val broken = take(1..3, frames = 10).also { it.file.writeText("not a recording") }
        val destination = root.resolve("out/none.wav")

        val result = runBlocking { compile.execute(1, plan(broken), destination) }

        assertIs<Result.NothingUsable>(result)
        assertFalse(destination.exists())
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────

    private var nextId = 1

    private fun take(
        verses: IntRange,
        frames: Int,
        inner: Map<String, Int> = emptyMap(),
        declaredAudioBytes: Long? = null,
        oldAppMetadata: Boolean = true,
        channels: Int = 1,
        sampleRate: Int = 44100
    ): LegacyTake {
        val id = nextId++
        val file = root.resolve("legacy/take-$id.wav")
        LegacyTakeWav.write(
            file, frames, seed = id, declaredAudioBytes = declaredAudioBytes,
            cues = if (oldAppMetadata) listOf("${verses.first}" to 0) + inner.toList() else emptyList(),
            metadataJson = if (oldAppMetadata) LegacyTakeWav.metadataJson("chunk", verses.first, verses.last, inner) else null,
            channels = channels, sampleRate = sampleRate
        )
        val metadata = LegacyTakeMetadata(
            chunking = LegacyChunking.CHUNKS,
            verses = verses,
            markers = inner.map { (label, frame) -> LegacyMarker(label, frame.toLong()) }
        )
        return LegacyTake(id, 1, 0, null, file, true, verses, metadata)
    }

    private fun plan(vararg takes: LegacyTake) =
        ChapterTakePlan(legacyProjectId = 1, versionLabel = "reg", selected = true, pieces = takes.map { ChapterPiece(it.verses!!, it) })

    private fun compiled(plan: ChapterTakePlan, destination: File = root.resolve("out/c01.wav")): Result.Compiled =
        assertIs<Result.Compiled>(runBlocking { compile.execute(1, plan, destination) })

    private fun markersIn(file: File) = OratureAudioFile(file).getMarkers().sortedBy { it.location }

    private fun pcmOf(file: File): ByteArray {
        val out = ByteArrayOutputStream()
        OratureAudioFile(file).reader().use { reader ->
            reader.open()
            val buffer = ByteArray(4096)
            while (reader.hasRemaining()) {
                val read = reader.getPcmBuffer(buffer)
                if (read <= 0) break
                out.write(buffer, 0, read)
            }
        }
        return out.toByteArray()
    }
}
