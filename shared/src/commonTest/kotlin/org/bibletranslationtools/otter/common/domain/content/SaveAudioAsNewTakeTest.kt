package org.bibletranslationtools.otter.common.domain.content

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.reactivex.Observable
import io.reactivex.Single
import kotlinx.coroutines.test.runTest
import org.bibletranslationtools.otter.common.audio.wav.IWaveFileCreator
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.data.workbook.AssociatedAudio
import org.bibletranslationtools.otter.common.data.workbook.Book
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.domain.resourcecontainer.project.ProjectFilesAccessor
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * Covers the take-save sequence both apps share. The recorder reached this through
 * `PlaybackViewModel.persistEditedFileAsNewTake` and Orature through
 * `OratureChapterReviewViewModel`; neither had a test, and :app-recorder had no test referencing
 * PlaybackViewModel at all. Getting the directory or the take number wrong here writes a take
 * the project cannot see, so it is worth pinning.
 */
class SaveAudioAsNewTakeTest {

    private val waveFileCreator = mockk<IWaveFileCreator>(relaxed = true)
    private val saveAudioAsNewTake = SaveAudioAsNewTake(TakeCreator(waveFileCreator))

    private lateinit var chapterAudioDir: File
    private lateinit var audio: AssociatedAudio

    /** A workbook whose file layout points at [chapterAudioDir], with one chapter. */
    private fun fixture(takeNumber: Int = 3): Triple<Workbook, Chapter, AssociatedAudio> {
        chapterAudioDir = File.createTempFile("chapter-audio", "").let {
            it.delete(); it.mkdirs(); it
        }

        audio = mockk(relaxed = true)
        coEvery { audio.getNewTakeNumberSuspend() } returns takeNumber

        val chapter = mockk<Chapter>(relaxed = true)
        every { chapter.sort } returns 1
        every { chapter.title } returns "1"
        every { chapter.label } returns "chapter"
        every { chapter.chunkCount } returns Single.just(0)
        every { chapter.contentType } returns ContentType.META
        every { chapter.audio } returns audio

        val language = mockk<Language>(relaxed = true)
        every { language.slug } returns "en"
        val metadata = mockk<ResourceMetadata>(relaxed = true)
        every { metadata.identifier } returns "ulb"

        val book = mockk<Book>(relaxed = true)
        every { book.slug } returns "gen"
        every { book.language } returns language
        every { book.resourceMetadata } returns metadata
        every { book.chapters } returns Observable.just(chapter)

        val accessor = mockk<ProjectFilesAccessor>(relaxed = true)
        every { accessor.getChapterAudioDir(any(), any()) } returns chapterAudioDir

        val workbook = mockk<Workbook>(relaxed = true)
        every { workbook.source } returns book
        every { workbook.target } returns book
        every { workbook.sourceMetadataSlug } returns "ulb"
        every { workbook.projectFilesAccessor } returns accessor

        return Triple(workbook, chapter, audio)
    }

    private fun sourceAudio(bytes: ByteArray = byteArrayOf(1, 2, 3, 4)): File =
        File.createTempFile("edited", ".wav").apply { writeBytes(bytes); deleteOnExit() }

    @Test
    fun `creates the take in the chapter audio directory and copies the audio in`() = runTest {
        val (workbook, chapter, _) = fixture()
        val bytes = byteArrayOf(9, 8, 7, 6, 5)
        val edited = sourceAudio(bytes)

        val take = saveAudioAsNewTake.execute(workbook, chapter, null, chapter, edited)

        assertEquals(
            chapterAudioDir.canonicalFile,
            take.file.parentFile.canonicalFile,
            "the take must land in the chapter's audio directory"
        )
        assertTrue(take.file.exists(), "the take file should have been written")
        assertEquals(
            bytes.toList(),
            take.file.readBytes().toList(),
            "the take's audio should be a copy of the file handed in"
        )
    }

    @Test
    fun `uses the next take number the associated audio reports`() = runTest {
        val (workbook, chapter, _) = fixture(takeNumber = 7)

        val take = saveAudioAsNewTake.execute(workbook, chapter, null, chapter, sourceAudio())

        assertEquals(7, take.number)
        assertTrue(
            take.file.name.endsWith(".wav"),
            "the generated name should carry the wav extension, was ${take.file.name}"
        )
    }

    /**
     * Registering is the whole point — a take created on disk but never inserted is invisible to the
     * project.
     */
    @Test
    fun `inserts the new take`() = runTest {
        val (workbook, chapter, audio) = fixture()

        val take = saveAudioAsNewTake.execute(workbook, chapter, null, chapter, sourceAudio())

        verify(exactly = 1) { audio.insertTake(take) }
    }

    /**
     * Selecting from here is a foreign-key violation, and this test is the guard against it coming
     * back. `WorkbookRepository` inserts on `Schedulers.io` and only assigns the take's id in that
     * subscribe; selecting synchronously on this thread beforehand read the take back out of
     * `takeMap` while its id was still 0 and wrote `selected_take_fk = 0`, which failed the insert
     * and broke saving a recording outright.
     *
     * The take still ends up selected: the repository selects every take it inserts, after the id
     * lands. The JavaFX app relied on exactly that — ChapterReviewViewModel, PeerEditViewModel and
     * NarrationHistory all insert and never select — and so does the recorder's commitStagedTake.
     */
    @Test
    fun `does not select the take itself`() = runTest {
        val (workbook, chapter, audio) = fixture()

        saveAudioAsNewTake.execute(workbook, chapter, null, chapter, sourceAudio())

        verify(exactly = 0) { audio.selectTake(any<Take>()) }
    }

    /**
     * A missing input file must fail loudly and leave nothing half-registered. The recorder wraps
     * this call in `runCatching` and surfaces the message, so throwing is the contract.
     *
     * The `getNewTakeNumberSuspend` assertion is the part worth having: `copyTo` would throw on a
     * missing file regardless, so without it this test passes whether or not the explicit guard
     * exists. What the guard actually buys is rejecting the input *before* allocating a take
     * number and building a Take for audio that was never going to arrive.
     */
    @Test
    fun `fails fast without touching the take list when the audio file is missing`() = runTest {
        val (workbook, chapter, audio) = fixture()
        val missing = File(chapterAudioDir, "not-there.wav")

        val error = assertFails { saveAudioAsNewTake.execute(workbook, chapter, null, chapter, missing) }

        assertTrue(
            error is IllegalStateException,
            "expected the guard's IllegalStateException, got ${error::class.simpleName}: ${error.message}"
        )
        assertTrue(
            missing.path in (error.message ?: ""),
            "the message should name the offending file, was: ${error.message}"
        )
        coVerify(exactly = 0) { audio.getNewTakeNumberSuspend() }
        verify(exactly = 0) { audio.insertTake(any()) }
        verify(exactly = 0) { audio.selectTake(any<Take>()) }
    }
}
