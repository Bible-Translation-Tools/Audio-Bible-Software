package org.bibletranslationtools.otter.common.domain.project

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.bibletranslationtools.otter.common.data.audio.VerseMarker
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.NewTake
import org.bibletranslationtools.otter.common.data.workbook.SaveResult
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.data.workbook.fixture.WorkbookBuilder
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.bibletranslationtools.otter.common.domain.audio.WriteTakeMarkers
import org.bibletranslationtools.otter.common.domain.narration.VerseNode
import org.bibletranslationtools.otter.common.domain.resourcecontainer.project.ProjectFilesAccessor
import org.bibletranslationtools.otter.common.domain.resourcecontainer.project.ProjectFilesService
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Covers the bug where an imported (never-opened) completed narration chapter shows 0 progress:
 * import restores the compiled chapter take but leaves active_verses.json empty, and import writes
 * takes with no DB markers, so the take's own embedded audio cues are the only surviving per-verse
 * signal. [ProjectCompletionStatus.getChapterNarrationProgress] must fall back to reading those cues
 * off the selected take when the working file alone says "nothing placed" — but must NOT report
 * complete if the take is itself missing markers for some verses.
 *
 * These drive the real [ProjectCompletionStatus] through a real
 * [org.bibletranslationtools.otter.common.domain.narration.ChapterRepresentation], with real files
 * on disk for active_verses.json / chapter_narration.pcm / the take's WAV — that class does file
 * I/O itself and is not designed to be swapped out from underneath ProjectCompletionStatus, so a
 * real instance backed by a temp directory is the only way to exercise the actual branch logic
 * rather than re-deriving it in the test.
 */
class ProjectCompletionStatusTest {

    private val projectFiles: ProjectFilesService = mockk()
    private val completionStatus = ProjectCompletionStatus(projectFiles)
    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun newChapterDir(): File {
        val dir = Files.createTempDirectory("chapter-completion-test").toFile()
        tempDirs.add(dir)
        return dir
    }

    /**
     * A workbook whose target chapter 1 has [verseCount] verse chunks sorted 1..verseCount (matching
     * [VerseMarker] labels "1".."N"), and whose project files resolve the chapter's directory to
     * [chapterDir]. A [selectedTake] file is saved as the chapter's selected take.
     */
    private suspend fun stubChapter(chapterDir: File, verseCount: Int, selectedTake: File? = null): Pair<Workbook, Chapter> {
        val workbook = WorkbookBuilder().build {
            target { chapter(1) { (1..verseCount).forEach { verse(it) } } }
        }
        val chapter = workbook.target.chapters().single()
        if (selectedTake != null) {
            val saved = chapter.audio.insertTake(NewTake(selectedTake, 1), select = true)
            assertIs<SaveResult.Saved<*>>(saved, "the selected take should have been saved")
        }
        val accessor: ProjectFilesAccessor = mockk(relaxed = true) {
            coEvery { getChapterAudioDir(any(), any()) } returns chapterDir
        }
        every { projectFiles.of(workbook) } returns accessor
        return workbook to chapter
    }

    /**
     * Writes active_verses.json directly (rather than driving it through
     * ChapterRepresentation.onVersesUpdated) so each test only sets up the state it actually
     * cares about. Uses the same VerseNode + AudioMarkerSerializer types ChapterRepresentation
     * itself serializes with, so this is the real on-disk shape, not a hand-rolled guess.
     */
    private fun writePartiallyPlacedWorkingFile(chapterDir: File, placedVerseNumbers: List<Int>) {
        val nodes = placedVerseNumbers.map { n ->
            VerseNode(true, VerseMarker(n, n, 0), mutableListOf(0..0))
        }
        val json = Json { ignoreUnknownKeys = true }
            .encodeToString(ListSerializer(VerseNode.serializer()), nodes)
        File(chapterDir, "active_verses.json").writeText(json)
    }

    /** A compiled chapter take's file: a real short WAV with [markerVerseNumbers] embedded as verse cues. */
    private fun takeWithMarkers(markerVerseNumbers: List<Int>): File {
        val file = File.createTempFile("chapter-take", ".wav").apply { deleteOnExit() }
        tempDirs.add(file) // deleteRecursively() on a plain file just deletes it
        OratureAudioFile(file, 1, 44_100, 16)
            .writer(append = true, buffered = true)
            .use { it.write(ByteArray(4_410 * 2)) } // 100ms of silence, mono 16-bit
        WriteTakeMarkers().execute(
            file,
            markerVerseNumbers.map { n -> VerseMarker(n, n, 0) },
            WriteTakeMarkers.ALL_CUE_TYPES
        )
        return file
    }

    @Test
    fun `imported chapter with empty working file but fully-marked take reads as complete`() = runTest {
        val chapterDir = newChapterDir()
        val take = takeWithMarkers(listOf(1, 2, 3))
        val (workbook, chapter) = stubChapter(chapterDir, verseCount = 3, selectedTake = take)

        val progress = completionStatus.getChapterNarrationProgress(workbook, chapter)

        assertEquals(1.0, progress, "a take whose cues cover every verse should read as fully complete")
    }

    @Test
    fun `imported chapter whose take is missing a verse marker is not counted complete`() = runTest {
        val chapterDir = newChapterDir()
        // Only 2 of the chapter's 3 verses have a cue on the take.
        val take = takeWithMarkers(listOf(1, 2))
        val (workbook, chapter) = stubChapter(chapterDir, verseCount = 3, selectedTake = take)

        val progress = completionStatus.getChapterNarrationProgress(workbook, chapter)

        assertEquals(2.0 / 3.0, progress)
        assertTrue(progress < 1.0, "a take missing a verse's marker must never score as complete")
    }

    @Test
    fun `no selected take falls back to the working file's own partial progress`() = runTest {
        val chapterDir = newChapterDir()
        writePartiallyPlacedWorkingFile(chapterDir, placedVerseNumbers = listOf(1, 2))
        val (workbook, chapter) = stubChapter(chapterDir, verseCount = 3, selectedTake = null)

        val progress = completionStatus.getChapterNarrationProgress(workbook, chapter)

        assertEquals(2.0 / 3.0, progress, "with no take to fall back to, the working file's own fraction wins")
    }

    @Test
    fun `no selected take and an empty working file reads as zero`() = runTest {
        val chapterDir = newChapterDir()
        val (workbook, chapter) = stubChapter(chapterDir, verseCount = 3, selectedTake = null)

        val progress = completionStatus.getChapterNarrationProgress(workbook, chapter)

        assertEquals(0.0, progress)
    }

    /**
     * Additive guarantee from the fix: a chapter the working file already reports as fully placed
     * must not be dragged down by an incomplete (or absent) take. This is what `maxOf` and the
     * `fileProgress >= 1.0` short-circuit in getChapterNarrationProgress are for.
     */
    @Test
    fun `a fully placed working file is never overridden by a weaker take`() = runTest {
        val chapterDir = newChapterDir()
        writePartiallyPlacedWorkingFile(chapterDir, placedVerseNumbers = listOf(1, 2, 3))
        // A take present but missing markers entirely - should be irrelevant once the file is complete.
        val take = takeWithMarkers(emptyList())
        val (workbook, chapter) = stubChapter(chapterDir, verseCount = 3, selectedTake = take)

        val progress = completionStatus.getChapterNarrationProgress(workbook, chapter)

        assertEquals(1.0, progress)
    }
}
