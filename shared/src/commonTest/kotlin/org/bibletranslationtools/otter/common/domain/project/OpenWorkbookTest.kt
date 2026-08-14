package org.bibletranslationtools.otter.common.domain.project

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.reactivex.Observable
import kotlinx.coroutines.test.runTest
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookDescriptorRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.workbook.Book
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Four Orature ViewModels open a project from a descriptor id; this is the sequence they share,
 * now in one place. The narration screen's tests cover it end-to-end through the ViewModel — these
 * pin the orchestration itself, including the ordering guarantee callers rely on.
 */
class OpenWorkbookTest {

    private val descriptorRepo: IWorkbookDescriptorRepository = mockk(relaxed = true)
    private val workbookRepo: IWorkbookRepository = mockk(relaxed = true)
    // Narration completion reads active_verses.json off disk through ChapterRepresentation, so it
    // is stubbed rather than exercised — these tests are about the orchestration, and the real one
    // needs a project directory.
    private val completionStatus: ProjectCompletionStatus = mockk(relaxed = true)
    private val openWorkbook = OpenWorkbook(descriptorRepo, workbookRepo, completionStatus)

    private val descriptorId = 7

    private fun chapter(sort: Int, completed: Boolean = false): Chapter = mockk {
        every { this@mockk.sort } returns sort
        every { hasSelectedAudio() } returns completed
        every { completionStatus.getChapterNarrationProgress(any(), this@mockk) } returns
            if (completed) 1.0 else 0.0
    }

    /** @param emitted the chapters as the target book's observable emits them */
    private fun stub(
        emitted: List<Chapter>,
        mode: ProjectMode = ProjectMode.NARRATION
    ): Workbook {
        val sourceCol: Collection = mockk(relaxed = true)
        val targetCol: Collection = mockk(relaxed = true)
        val descriptor: WorkbookDescriptor = mockk {
            every { sourceCollection } returns sourceCol
            every { targetCollection } returns targetCol
            every { this@mockk.mode } returns mode
        }
        val targetBook: Book = mockk {
            every { chapters } returns Observable.fromIterable(emitted)
        }
        val workbook: Workbook = mockk(relaxed = true) {
            every { target } returns targetBook
        }
        coEvery { descriptorRepo.getByIdSuspend(descriptorId) } returns descriptor
        every { workbookRepo.get(sourceCol, targetCol) } returns workbook
        return workbook
    }

    @Test
    fun `resolves the descriptor into its workbook and mode`() = runTest {
        val workbook = stub(listOf(chapter(1)), mode = ProjectMode.DIALECT)

        val opened = openWorkbook.openWithChapters(descriptorId)

        assertSame(workbook, opened.workbook)
        assertEquals(ProjectMode.DIALECT, opened.mode)
    }

    @Test
    fun `open resolves the workbook and mode without chapters`() = runTest {
        val workbook = stub(listOf(chapter(1)), mode = ProjectMode.TRANSLATION)

        val opened = openWorkbook.open(descriptorId)

        assertSame(workbook, opened.workbook)
        assertEquals(ProjectMode.TRANSLATION, opened.mode)
    }

    /**
     * The whole reason [OpenWorkbook.open] exists alongside [OpenWorkbook.openWithChapters]:
     * screens with no chapter list should not pay a `hasSelectedAudio()` query per chapter. If
     * `open` ever starts draining the chapter observable, this catches it.
     */
    @Test
    fun `open does not touch chapter completion`() = runTest {
        val chapters = listOf(chapter(1, completed = true), chapter(2), chapter(3))
        stub(chapters, mode = ProjectMode.TRANSLATION)

        openWorkbook.open(descriptorId)

        chapters.forEach { verify(exactly = 0) { it.hasSelectedAudio() } }
    }

    @Test
    fun `open fails naming the descriptor when the id does not resolve`() = runTest {
        coEvery { descriptorRepo.getByIdSuspend(descriptorId) } returns null

        val error = assertFails { openWorkbook.open(descriptorId) }

        assertTrue("$descriptorId" in (error.message ?: ""), "was: ${error.message}")
    }

    /**
     * The chapter observable emits in whatever order the query returns. Callers index the list
     * positionally to build the chapter grid and to step next/previous, so sorting here is the
     * contract rather than an incidental detail.
     */
    @Test
    fun `returns chapters ordered by sort regardless of emission order`() = runTest {
        stub(listOf(chapter(3), chapter(1), chapter(2)))

        val opened = openWorkbook.openWithChapters(descriptorId)

        assertEquals(listOf(1, 2, 3), opened.chapters.map { it.sort })
    }

    @Test
    fun `maps each chapter's completion by its sort`() = runTest {
        stub(listOf(chapter(1, completed = false), chapter(2, completed = true), chapter(3)))

        val opened = openWorkbook.openWithChapters(descriptorId)

        assertEquals(
            mapOf(1 to false, 2 to true, 3 to false),
            opened.completedByChapterSort
        )
    }

    /**
     * The export dialog greyed out its own Export button for a finished narration project because
     * completion was asked as `hasSelectedAudio()` in every mode. That is the TRANSLATION question:
     * a narrated chapter's work is in active_verses.json, and its compiled take is a later artifact
     * that may legitimately not exist yet — so the chapter scored 0 while the home screen, which
     * already branched on mode, showed the same book 100%.
     */
    @Test
    fun `narration completion comes from narration progress, not the chapter take`() = runTest {
        val narrated: Chapter = mockk {
            every { sort } returns 1
            // No compiled take, yet every verse is recorded.
            every { hasSelectedAudio() } returns false
            every { completionStatus.getChapterNarrationProgress(any(), this@mockk) } returns 1.0
        }
        stub(listOf(narrated), mode = ProjectMode.NARRATION)

        val opened = openWorkbook.openWithChapters(descriptorId)

        assertEquals(mapOf(1 to true), opened.completedByChapterSort)
    }

    @Test
    fun `a partly narrated chapter is not complete`() = runTest {
        val partial: Chapter = mockk {
            every { sort } returns 1
            every { hasSelectedAudio() } returns true
            every { completionStatus.getChapterNarrationProgress(any(), this@mockk) } returns 0.5
        }
        stub(listOf(partial), mode = ProjectMode.DIALECT)

        val opened = openWorkbook.openWithChapters(descriptorId)

        assertEquals(mapOf(1 to false), opened.completedByChapterSort)
    }

    /** Translation still asks the take question — that mode has no per-verse narration state. */
    @Test
    fun `translation completion still comes from the selected take`() = runTest {
        stub(listOf(chapter(1, completed = true), chapter(2)), mode = ProjectMode.TRANSLATION)

        val opened = openWorkbook.openWithChapters(descriptorId)

        assertEquals(mapOf(1 to true, 2 to false), opened.completedByChapterSort)
    }

    @Test
    fun `a book with no chapters opens with empty lists rather than failing`() = runTest {
        stub(emptyList())

        val opened = openWorkbook.openWithChapters(descriptorId)

        assertTrue(opened.chapters.isEmpty())
        assertTrue(opened.completedByChapterSort.isEmpty())
    }

    /**
     * A descriptor id that no longer resolves means the caller was navigated to a project that has
     * been deleted. The narration ViewModel turns this into its error state, and its test asserts
     * the message names the descriptor — so the message is load-bearing, not just diagnostic.
     */
    @Test
    fun `fails naming the descriptor when the id does not resolve`() = runTest {
        coEvery { descriptorRepo.getByIdSuspend(descriptorId) } returns null

        val error = assertFails { openWorkbook.openWithChapters(descriptorId) }

        val message = error.message ?: ""
        assertTrue("descriptor" in message, "expected the message to mention the descriptor: $message")
        assertTrue("$descriptorId" in message, "expected the message to carry the id: $message")
    }
}
