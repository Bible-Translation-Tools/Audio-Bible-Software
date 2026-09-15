package org.bibletranslationtools.bttrecorder2.takes

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.reactivex.Single
import org.bibletranslationtools.otter.common.api.persistence.repositories.ITakeRepository
import org.bibletranslationtools.otter.common.audio.wav.WavFile
import org.bibletranslationtools.otter.common.data.audio.ChapterMarker
import org.bibletranslationtools.otter.common.data.primitives.CheckingStatus
import org.bibletranslationtools.otter.common.data.primitives.Content
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.data.primitives.Take
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.bibletranslationtools.otter.common.domain.audio.WriteTakeMarkers
import org.bibletranslationtools.otter.common.domain.content.FileNamer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What a migrated take looks like on disk.
 *
 * The single cue is required rather than decorative: `SourceProjectExporter` skips any take whose
 * cue list is empty, and the recorder offers that export type, so a cue-less take would be missing
 * from a source-audio export. Clearing the legacy metadata matters for the opposite reason, its cues
 * being labelled with bare verse numbers that `VerseMarkerParser` turns into unknown markers, and
 * its `LIST/INFO/IART` block being non-standard.
 *
 * WAVs are generated here rather than committed, `*.wav` being git-ignored repo-wide.
 */
class WriteTakeFromAudioTest {

    private val work = createTempDirectory("legacy-take-test").toFile()
    private val destinationDir = File(work, "takes/c01").apply { mkdirs() }

    private val insertedTakes = mutableListOf<Take>()
    private val takeRepository = mockk<ITakeRepository>(relaxed = true).also { repo ->
        val take = slot<Take>()
        every { repo.getByContent(any(), any()) } returns Single.just(emptyList())
        every { repo.insertForContent(capture(take), any()) } answers {
            insertedTakes += take.captured
            Single.just(insertedTakes.size)
        }
    }

    private val migrator = WriteTakeFromAudio(takeRepository, WriteTakeMarkers())

    /** A chunk-mode unit covering verses 1-3, the case the whole merge exists for. */
    private val content = Content(
        sort = 1,
        labelKey = "verse",
        start = 1,
        end = 3,
        selectedTake = null,
        text = "merged",
        format = "usfm",
        type = ContentType.TEXT,
        draftNumber = 1
    ).also { it.id = 42 }

    private val namer = FileNamer(
        start = 1,
        end = 3,
        sort = 1,
        contentType = ContentType.TEXT,
        languageSlug = "aaa",
        bookSlug = "jas",
        rcSlug = FileNamer.DEFAULT_RC_SLUG,
        chunkCount = 10,
        chapterCount = 5,
        chapterTitle = "1",
        chapterSort = 1
    )

    @AfterTest
    fun tearDown() {
        work.deleteRecursively()
    }

    @Test
    fun `a migrated take carries exactly one orature verse cue at frame zero`() {
        val source = legacyTake("legacy.wav")

        val result = migrator.execute(source, destinationDir, namer, content, 1, select = true)

        assertTrue(result is WriteTakeFromAudio.Result.Copied, "expected a copy, got $result")
        val cues = OratureAudioFile(File(destinationDir, "aaa_reg_jas_c01_v01_t1.wav")).getCues()
        assertEquals(1, cues.size, "one cue, matching markerSpecsForCurrentTake for a chunk take")
        assertEquals("orature-vm-1-3", cues.single().label)
        assertEquals(0, cues.single().location)
    }

    @Test
    fun `the cue list is non-empty, which is what SourceProjectExporter gates on`() {
        val source = legacyTake("legacy.wav")

        migrator.execute(source, destinationDir, namer, content, 1, select = false)

        val copied = File(destinationDir, "aaa_reg_jas_c01_v01_t1.wav")
        assertFalse(
            OratureAudioFile(copied).getCues().isEmpty(),
            "an empty cue list makes SourceProjectExporter.exportTake skip the take silently"
        )
    }

    @Test
    fun `legacy cue labels and the IART metadata block are gone`() {
        val source = legacyTake("legacy.wav")
        // The fixture really does carry the legacy shapes before migration.
        assertTrue(source.readBytes().containsAscii("IART"))

        migrator.execute(source, destinationDir, namer, content, 1, select = false)

        val copied = File(destinationDir, "aaa_reg_jas_c01_v01_t1.wav")
        assertFalse(
            copied.readBytes().containsAscii("IART"),
            "the non-standard IART block must not survive"
        )
        // The surviving cue is the merged unit, not the legacy per-verse ones.
        assertEquals(listOf("orature-vm-1-3"), OratureAudioFile(copied).getCues().map { it.label })
    }

    @Test
    fun `left in place, legacy bare-number cues would read as separate verse markers`() {
        // Why clearing them matters: `VerseMarkerParser` promotes lone-digit labels to verse
        // markers, so a bare-numbered WAV can still be imported, and keeps them as unknown markers
        // besides. A legacy chunk take covering 1-3 would then present as verses 1, 2 and 3,
        // contradicting the single merged unit its content row describes.
        val labels = OratureAudioFile(legacyTake("legacy.wav")).getCues().map { it.label }

        assertTrue(labels.containsAll(listOf("orature-vm-1", "orature-vm-2", "orature-vm-3")))
        assertTrue(labels.containsAll(listOf("1", "2", "3")))
    }

    @Test
    fun `the audio is copied unchanged`() {
        val source = legacyTake("legacy.wav")
        val expectedFrames = OratureAudioFile(source).totalFrames

        val result = migrator.execute(source, destinationDir, namer, content, 1, select = false)

        val copied = File(destinationDir, "aaa_reg_jas_c01_v01_t1.wav")
        assertEquals(expectedFrames, OratureAudioFile(copied).totalFrames)
        assertEquals(expectedFrames, (result as WriteTakeFromAudio.Result.Copied).frames)
        assertContentEqualsPcm(source, copied)
    }

    @Test
    fun `the take is registered UNCHECKED with no marker rows`() {
        migrator.execute(legacyTake("legacy.wav"), destinationDir, namer, content, 1, select = false)

        val take = insertedTakes.single()
        assertEquals(CheckingStatus.UNCHECKED, take.checkingStatus)
        assertEquals(null, take.checksum)
        assertTrue(take.markers.isEmpty(), "marker rows come from the file's cues, not the DB")
    }

    @Test
    fun `the chosen legacy take becomes the selected take`() {
        migrator.execute(legacyTake("legacy.wav"), destinationDir, namer, content, 1, select = true)

        assertNotNull(content.selectedTake)
        assertEquals(insertedTakes.single().filename, content.selectedTake!!.filename)
    }

    @Test
    fun `a take already migrated by an interrupted run is recognised, not duplicated`() {
        val source = legacyTake("legacy.wav")
        migrator.execute(source, destinationDir, namer, content, 1, select = false)
        val alreadyThere = insertedTakes.single()
        every { takeRepository.getByContent(any(), any()) } returns Single.just(listOf(alreadyThere))

        val result = migrator.execute(source, destinationDir, namer, content, 1, select = false)

        assertTrue(result is WriteTakeFromAudio.Result.AlreadyPresent, "got $result")
        assertEquals(alreadyThere.filename, (result as WriteTakeFromAudio.Result.AlreadyPresent).take.filename)
        assertEquals(1, insertedTakes.size, "no second row for the same take")
    }

    @Test
    fun `a slot occupied by different audio moves to the next take number`() {
        // A take recorded between an interrupted run and its retry must not be overwritten.
        val usersTake = legacyTake("users.wav", frames = 500)
            .copyTo(File(destinationDir, "aaa_reg_jas_c01_v01_t1.wav"))
        val existing = Take(
            filename = usersTake.name,
            path = usersTake,
            number = 1,
            created = java.time.LocalDate.now(),
            deleted = null,
            played = false,
            checkingStatus = CheckingStatus.UNCHECKED,
            checksum = null,
            markers = emptyList()
        )
        every { takeRepository.getByContent(any(), any()) } returns Single.just(listOf(existing))

        val result = migrator.execute(
            legacyTake("legacy.wav", frames = 1000), destinationDir, namer, content, 1, select = false
        )

        assertTrue(result is WriteTakeFromAudio.Result.Copied)
        assertEquals(2, (result as WriteTakeFromAudio.Result.Copied).take.number)
        assertTrue(File(destinationDir, "aaa_reg_jas_c01_v01_t2.wav").isFile)
        assertEquals(500, OratureAudioFile(usersTake).totalFrames, "the user's take is untouched")
    }

    @Test
    fun `a different recording of the same length is renumbered, not taken as already migrated`() {
        // Two projects' takes for one verse can run the same length, so comparing frame counts
        // alone would report "already present", dropping the second take and, if it was the chosen
        // one, leaving the selection on the other project's audio.
        val first = legacyTake("first.wav", frames = 1000, seed = 0)
        migrator.execute(first, destinationDir, namer, content, 1, select = false)
        val migrated = insertedTakes.single()
        every { takeRepository.getByContent(any(), any()) } returns Single.just(listOf(migrated))

        val second = legacyTake("second.wav", frames = 1000, seed = 7)
        val result = migrator.execute(second, destinationDir, namer, content, 1, select = false)

        assertTrue(result is WriteTakeFromAudio.Result.Copied, "got $result")
        assertEquals(2, (result as WriteTakeFromAudio.Result.Copied).take.number)
        assertEquals(2, insertedTakes.size)
        assertContentEqualsPcm(second, result.take.path)
    }

    @Test
    fun `takes from three merged legacy projects are renumbered in order, none lost`() {
        // aa/ulb/jas, aa/udb/jas and aa/reg/jas migrate as one ULB project, so their takes for a
        // single unit occupy consecutive slots.
        val sources = listOf(
            legacyTake("ulb.wav", frames = 1000, seed = 1),
            legacyTake("udb.wav", frames = 1100, seed = 2),
            legacyTake("reg.wav", frames = 1000, seed = 3)
        )
        // Each project numbers its own takes from 1, so the slot walk is what separates them.
        every { takeRepository.getByContent(any(), any()) } answers { Single.just(insertedTakes.toList()) }

        val results = sources.map {
            migrator.execute(it, destinationDir, namer, content, 1, select = false)
        }

        assertTrue(results.all { it is WriteTakeFromAudio.Result.Copied }, results.toString())
        assertEquals(
            listOf(1, 2, 3),
            results.map { (it as WriteTakeFromAudio.Result.Copied).take.number }
        )
        assertEquals(3, insertedTakes.size, "no take may be dropped in a merge")
        // Distinct files, each holding its own project's audio.
        assertEquals(3, insertedTakes.map { it.filename }.distinct().size)
        sources.zip(results).forEach { (source, result) ->
            assertContentEqualsPcm(source, (result as WriteTakeFromAudio.Result.Copied).take.path)
        }
    }

    @Test
    fun `a resumed run still recognises its own work when another project has merged in`() {
        every { takeRepository.getByContent(any(), any()) } answers { Single.just(insertedTakes.toList()) }
        val ulb = legacyTake("ulb.wav", frames = 1000, seed = 1)
        val reg = legacyTake("reg.wav", frames = 1000, seed = 3)
        migrator.execute(ulb, destinationDir, namer, content, 1, select = false)
        migrator.execute(reg, destinationDir, namer, content, 1, select = false)

        // Retried after an interruption: neither take may be copied a second time.
        val retryUlb = migrator.execute(ulb, destinationDir, namer, content, 1, select = false)
        val retryReg = migrator.execute(reg, destinationDir, namer, content, 1, select = false)

        assertTrue(retryUlb is WriteTakeFromAudio.Result.AlreadyPresent, "got $retryUlb")
        assertTrue(retryReg is WriteTakeFromAudio.Result.AlreadyPresent, "got $retryReg")
        assertEquals(1, (retryUlb as WriteTakeFromAudio.Result.AlreadyPresent).take.number)
        assertEquals(2, (retryReg as WriteTakeFromAudio.Result.AlreadyPresent).take.number)
        assertEquals(2, insertedTakes.size, "a resumed run must not duplicate rows")
    }

    @Test
    fun `a supplied marker is written instead of a verse marker`() {
        // A narration-imported unit can be a book or chapter title, which carries its own kind of
        // marker. Defaulting to a verse marker would label the chapter title as a verse.
        val result = migrator.execute(
            legacyTake("title.wav"), destinationDir, namer, content, 1,
            select = false,
            marker = ChapterMarker(1, 0)
        )

        assertTrue(result is WriteTakeFromAudio.Result.Copied, "got $result")
        val cues = OratureAudioFile((result as WriteTakeFromAudio.Result.Copied).take.path).getCues()
        assertEquals(1, cues.size, "exactly one cue")
        assertEquals("orature-chapter-1", cues.single().label)
    }

    @Test
    fun `without a marker the unit's verse range is written`() {
        val result = migrator.execute(
            legacyTake("verses.wav"), destinationDir, namer, content, 1, select = false
        )

        val cues = OratureAudioFile((result as WriteTakeFromAudio.Result.Copied).take.path).getCues()
        assertEquals("orature-vm-1-3", cues.single().label, "the content row spans verses 1-3")
    }

    @Test
    fun `an unreadable take is skipped rather than throwing`() {
        val broken = File(work, "broken.wav").apply { writeText("not a wav at all") }

        val result = migrator.execute(broken, destinationDir, namer, content, 1, select = false)

        assertTrue(result is WriteTakeFromAudio.Result.Skipped)
        assertTrue(insertedTakes.isEmpty())
    }

    @Test
    fun `a missing take file is skipped rather than throwing`() {
        val result = migrator.execute(
            File(work, "absent.wav"), destinationDir, namer, content, 1, select = false
        )

        assertTrue(result is WriteTakeFromAudio.Result.Skipped)
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * A WAV shaped like the legacy recorder's: canonical 44-byte header and 44.1 kHz / mono /
     * 16-bit audio (which is why migration never transcodes), carrying bare-numbered verse cues
     * and a `LIST/INFO/IART` block holding the legacy JSON metadata.
     */
    private fun legacyTake(name: String, frames: Int = 1000, seed: Int = 0): File {
        val file = File(work, name)
        WavFile(file, 1, 44100, 16).let { wav ->
            wav.writer().use { out ->
                // Non-silent, so a truncated or re-encoded copy differs in bytes. [seed] shifts
                // the pattern, so two takes of equal length can hold different audio.
                out.write(ByteArray(frames * 2) { ((it + seed) % 251).toByte() })
            }
        }
        // The legacy app labelled verse markers by frame order as `startVerse + i`, i.e. bare
        // numbers, which the Orature cue parser does not match.
        WavFile(file).let { wav ->
            wav.metadata.addCue(0, "1")
            wav.metadata.addCue(frames / 3, "2")
            wav.metadata.addCue(frames * 2 / 3, "3")
            wav.update()
        }
        appendIartChunk(file)
        return file
    }

    /**
     * Appends the legacy `LIST/INFO/IART` chunk that `WavMetadata.toJSON` produced, and repairs the
     * RIFF size so the result is a well-formed file that Orature's parser will skip over.
     */
    private fun appendIartChunk(file: File) {
        val payload = """{"language":"aaa","version":"ulb","book":"jas","mode":"chunk","startv":"1","endv":"3"}"""
            .toByteArray(StandardCharsets.US_ASCII)
        val padded = if (payload.size % 2 == 0) payload else payload + 0
        val chunk = ByteBuffer.allocate(20 + padded.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("LIST".toByteArray(StandardCharsets.US_ASCII))
            putInt(4 + 8 + padded.size)
            put("INFO".toByteArray(StandardCharsets.US_ASCII))
            put("IART".toByteArray(StandardCharsets.US_ASCII))
            putInt(padded.size)
            put(padded)
        }.array()

        file.appendBytes(chunk)
        val bytes = file.readBytes()
        // RIFF size = everything after the first 8 bytes.
        ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size - 8)
        file.writeBytes(bytes)
    }

    private fun assertContentEqualsPcm(source: File, copied: File) {
        assertTrue(
            pcm(source).contentEquals(pcm(copied)),
            "PCM must be byte-identical: metadata is rewritten, audio is not"
        )
    }

    private fun pcm(file: File): ByteArray {
        val frames = OratureAudioFile(file).totalFrames
        val bytes = ByteArray(frames * 2)
        OratureAudioFile(file).reader().use { reader ->
            reader.open()
            var offset = 0
            val buffer = ByteArray(4096)
            while (reader.hasRemaining() && offset < bytes.size) {
                val read = reader.getPcmBuffer(buffer)
                if (read <= 0) break
                val take = minOf(read, bytes.size - offset)
                buffer.copyInto(bytes, offset, 0, take)
                offset += take
            }
        }
        return bytes
    }

    private fun ByteArray.containsAscii(needle: String): Boolean {
        val target = needle.toByteArray(StandardCharsets.US_ASCII)
        outer@ for (i in 0..size - target.size) {
            for (j in target.indices) if (this[i + j] != target[j]) continue@outer
            return true
        }
        return false
    }
}
