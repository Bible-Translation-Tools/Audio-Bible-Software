package org.bibletranslationtools.otter.common.domain.content

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.SaveFailure
import org.bibletranslationtools.otter.common.data.workbook.SaveResult
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.data.workbook.fixture.FakeWorkbookStore
import org.bibletranslationtools.otter.common.data.workbook.fixture.WorkbookBuilder
import org.bibletranslationtools.otter.common.domain.resourcecontainer.project.ProjectFilesAccessor
import org.bibletranslationtools.otter.common.domain.resourcecontainer.project.ProjectFilesService
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Covers the take-save sequence both apps share. The recorder reached this through
 * `PlaybackViewModel.persistEditedFileAsNewTake` and Orature through
 * `OratureChapterReviewViewModel`; neither had a test, and :app-recorder had no test referencing
 * PlaybackViewModel at all. Getting the directory or the take number wrong here writes a take
 * the project cannot see, so it is worth pinning.
 */
class SaveAudioAsNewTakeTest {

    private val store = FakeWorkbookStore()
    private val accessor = mockk<ProjectFilesAccessor>(relaxed = true)
    private val projectFiles = mockk<ProjectFilesService>()
    private val saveAudioAsNewTake = SaveAudioAsNewTake(projectFiles)

    private lateinit var chapterAudioDir: File

    /**
     * A workbook whose file layout points at [chapterAudioDir], with one chapter whose next take
     * number is [takeNumber] (it already has takes 1 to takeNumber - 1).
     */
    private suspend fun fixture(takeNumber: Int = 3): Pair<Workbook, Chapter> {
        chapterAudioDir = File.createTempFile("chapter-audio", "").let {
            it.delete(); it.mkdirs(); it.deleteOnExit(); it
        }

        val workbook = WorkbookBuilder(store).build {
            target {
                chapter(1) { (1 until takeNumber).forEach { take("chapter_t$it.wav") } }
            }
        }
        coEvery { accessor.getChapterAudioDir(any(), any()) } returns chapterAudioDir
        every { projectFiles.of(workbook) } returns accessor

        val chapter = workbook.target.chapters().single()
        store.calls.clear()
        return workbook to chapter
    }

    private fun sourceAudio(bytes: ByteArray = byteArrayOf(1, 2, 3, 4)): File =
        File.createTempFile("edited", ".wav").apply { writeBytes(bytes); deleteOnExit() }

    /** The store's writes, without its reads (the file namer loads the chapter's chunks). */
    private fun writes() = store.calls.filterNot { it.startsWith("load") }

    private fun SaveResult<Take>.saved(): Take = assertIs<SaveResult.Saved<Take>>(this).value

    @Test
    fun `creates the take in the chapter audio directory and copies the audio in`() = runTest {
        val (workbook, chapter) = fixture()
        val bytes = byteArrayOf(9, 8, 7, 6, 5)
        val edited = sourceAudio(bytes)

        val take = saveAudioAsNewTake.execute(workbook, chapter, null, chapter, edited).saved()

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
        val (workbook, chapter) = fixture(takeNumber = 7)

        val take = saveAudioAsNewTake.execute(workbook, chapter, null, chapter, sourceAudio()).saved()

        assertEquals(7, take.number)
        assertTrue(
            take.file.name.endsWith(".wav"),
            "the generated name should carry the wav extension, was ${take.file.name}"
        )
    }

    /**
     * Registering is the whole point — a take created on disk but never inserted is invisible to the
     * project. It must be in the store, not only in the chapter's in-memory state.
     */
    @Test
    fun `inserts the new take`() = runTest {
        val (workbook, chapter) = fixture()

        val take = saveAudioAsNewTake.execute(workbook, chapter, null, chapter, sourceAudio()).saved()

        assertEquals(listOf("addTake"), writes())
        assertTrue(take in store.saved(chapter.audio.owner).takes, "the take was not saved")
        assertTrue(take in chapter.audio.getAllTakes(), "the chapter does not show the saved take")
    }

    /**
     * Selecting in a separate write is the foreign-key violation this used to guard against: the old
     * repository assigned the take's id asynchronously, and selecting beforehand wrote
     * `selected_take_fk = 0`, which failed the insert and broke saving a recording outright. The take
     * is now saved and selected in one write, so nothing can select it before it has an id — and
     * both the store and the chapter agree it is the selected take.
     */
    @Test
    fun `saves and selects the take in one write`() = runTest {
        val (workbook, chapter) = fixture()

        val take = saveAudioAsNewTake.execute(workbook, chapter, null, chapter, sourceAudio()).saved()

        assertEquals(listOf("addTake"), writes(), "selecting must not be a separate write")
        assertEquals(take.id, store.saved(chapter.audio.owner).selected, "the saved take was not selected")
        assertEquals(take, chapter.audio.getSelectedTake())
    }

    /**
     * A take number that is already taken on disk, by a file no row in this element knows about
     * (an older chunk layout's take, an orphan), must not have its audio replaced.
     */
    @Test
    fun `never overwrites an existing take file`() = runTest {
        val (workbook, chapter) = fixture(takeNumber = 1)
        val first = saveAudioAsNewTake.execute(workbook, chapter, null, chapter, sourceAudio(byteArrayOf(1))).saved()
        // Forget the first take, as an orphan on disk would be: the next number is 1 again.
        val again = WorkbookBuilder(store).build { target { chapter(1) } }
        every { projectFiles.of(again) } returns accessor
        val orphanChapter = again.target.chapters().single()
        assertEquals(1, orphanChapter.audio.getNewTakeNumber())

        val second = saveAudioAsNewTake
            .execute(again, orphanChapter, null, orphanChapter, sourceAudio(byteArrayOf(2)))
            .saved()

        assertNotEquals(first.file.canonicalFile, second.file.canonicalFile)
        assertEquals(listOf<Byte>(1), first.file.readBytes().toList(), "the existing take's audio was replaced")
        assertEquals(listOf<Byte>(2), second.file.readBytes().toList())
    }

    /**
     * A missing input file must fail loudly and leave nothing half-registered. The recorder wraps
     * this call in `runCatching` and surfaces the message, so throwing is the contract.
     *
     * The empty-directory assertion is the part worth having: `copyTo` would throw on a missing file
     * regardless, so without it this test passes whether or not the explicit guard exists. What the
     * guard actually buys is rejecting the input *before* reserving a take file for audio that was
     * never going to arrive.
     */
    @Test
    fun `fails fast without touching the take list when the audio file is missing`() = runTest {
        val (workbook, chapter) = fixture()
        val takesBefore = chapter.audio.getAllTakes()
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
        coVerify(exactly = 0) { accessor.getChapterAudioDir(any(), any()) }
        assertTrue(chapterAudioDir.listFiles().isNullOrEmpty(), "a take file was reserved for missing audio")
        assertTrue(writes().isEmpty(), "nothing should have been written, calls were ${store.calls}")
        assertEquals(takesBefore, chapter.audio.getAllTakes())
    }

    /** A save that fails leaves no take behind: not in the store, not in the chapter, not on disk. */
    @Test
    fun `a failed save removes the copied file and reports the failure`() = runTest {
        val (workbook, chapter) = fixture()
        val takesBefore = chapter.audio.getAllTakes()
        val savedBefore = store.saved(chapter.audio.owner).takes
        store.failNextWrite()

        val result = saveAudioAsNewTake.execute(workbook, chapter, null, chapter, sourceAudio())

        assertEquals(SaveFailure.COULD_NOT_SAVE, assertIs<SaveResult.Failed>(result).failure)
        assertTrue(chapterAudioDir.listFiles().isNullOrEmpty(), "the copied take file was left behind")
        assertEquals(savedBefore, store.saved(chapter.audio.owner).takes)
        assertEquals(takesBefore, chapter.audio.getAllTakes())
        assertFalse(chapter.hasSelectedAudio())
    }
}
