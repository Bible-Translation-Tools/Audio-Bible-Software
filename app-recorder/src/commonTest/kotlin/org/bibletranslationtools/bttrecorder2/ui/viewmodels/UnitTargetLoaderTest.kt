package org.bibletranslationtools.bttrecorder2.ui.viewmodels

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.reactivex.Observable
import kotlinx.coroutines.test.runTest
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.bibletranslationtools.otter.common.data.workbook.Book
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Chunk
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The expansion both recorder screens navigate by. Neither `RecorderViewModel.loadTarget` nor
 * `PlaybackViewModel.loadTarget` had any test before this — nothing in :app-recorder referenced
 * either ViewModel — so the flattening order and the not-found signal were entirely unpinned.
 */
class UnitTargetLoaderTest {

    private val workbookRepository: IWorkbookRepository = mockk(relaxed = true)
    private val loader = UnitTargetLoader(workbookRepository)

    private val sourceId = 11
    private val targetId = 22

    private fun chunk(sort: Int): Chunk = mockk { every { this@mockk.sort } returns sort }

    private fun chapter(sort: Int, chunks: List<Chunk> = emptyList()): Chapter = mockk {
        every { this@mockk.sort } returns sort
        every { chunksSuspend } returns suspend { chunks }
    }

    /** A single project whose collection ids match [sourceId]/[targetId]. */
    private fun stubProject(
        chapters: List<Chapter>,
        source: Int = sourceId,
        target: Int = targetId
    ): Workbook {
        val sourceBook: Book = mockk { every { collectionId } returns source }
        val targetBook: Book = mockk {
            every { collectionId } returns target
            every { this@mockk.chapters } returns Observable.fromIterable(chapters)
        }
        val workbook: Workbook = mockk(relaxed = true) {
            every { this@mockk.source } returns sourceBook
            every { this@mockk.target } returns targetBook
        }
        coEvery { workbookRepository.getProjectsSuspend() } returns listOf(workbook)
        return workbook
    }

    /**
     * The flattening order is the navigation order: prev/next walks this list, so a chapter must be
     * immediately followed by its own chunks, in chunk order.
     */
    @Test
    fun `expands each chapter into a chapter target followed by its chunks in order`() = runTest {
        stubProject(
            listOf(
                chapter(1, listOf(chunk(2), chunk(1))),
                chapter(2, listOf(chunk(1)))
            )
        )

        val loaded = loader.load(sourceId, targetId, chapterNumber = 1, unitNumber = null)!!

        assertEquals(
            listOf(1 to null, 1 to 1, 1 to 2, 2 to null, 2 to 1),
            loaded.targets.map { it.chapter.sort to it.chunk?.sort },
            "chapter target first, then its chunks ascending"
        )
    }

    @Test
    fun `orders chapters by sort regardless of emission order`() = runTest {
        stubProject(listOf(chapter(3), chapter(1), chapter(2)))

        val loaded = loader.load(sourceId, targetId, chapterNumber = 1, unitNumber = null)!!

        assertEquals(listOf(1, 2, 3), loaded.targets.map { it.chapter.sort })
    }

    @Test
    fun `a null unit resolves to the chapter-level target`() = runTest {
        stubProject(listOf(chapter(1, listOf(chunk(1))), chapter(2, listOf(chunk(1)))))

        val loaded = loader.load(sourceId, targetId, chapterNumber = 2, unitNumber = null)!!

        val requested = loaded.targets[loaded.requestedIndex]
        assertEquals(2, requested.chapter.sort)
        assertNull(requested.chunk, "a null unit means the whole chapter")
    }

    @Test
    fun `a unit number resolves to that chunk within that chapter`() = runTest {
        stubProject(listOf(chapter(1, listOf(chunk(1), chunk(2))), chapter(2, listOf(chunk(1)))))

        val loaded = loader.load(sourceId, targetId, chapterNumber = 1, unitNumber = 2)!!

        val requested = loaded.targets[loaded.requestedIndex]
        assertEquals(1, requested.chapter.sort)
        assertEquals(2, requested.chunk?.sort)
    }

    /**
     * -1 rather than a defaulted index, because the two screens disagree about the fallback:
     * playback opens target 0, the recorder skips to the first chunk. Collapsing that decision in
     * here would silently give one of them the other's behaviour.
     */
    @Test
    fun `reports -1 when the requested chapter and unit match nothing`() = runTest {
        stubProject(listOf(chapter(1, listOf(chunk(1)))))

        val loaded = loader.load(sourceId, targetId, chapterNumber = 99, unitNumber = 4)!!

        assertEquals(-1, loaded.requestedIndex)
        assertTrue(loaded.targets.isNotEmpty(), "the targets are still usable for a fallback")
    }

    @Test
    fun `returns the matching workbook`() = runTest {
        val workbook = stubProject(listOf(chapter(1)))

        val loaded = loader.load(sourceId, targetId, chapterNumber = 1, unitNumber = null)!!

        assertSame(workbook, loaded.workbook)
    }

    @Test
    fun `returns null when no project matches both collection ids`() = runTest {
        stubProject(listOf(chapter(1)), source = sourceId, target = 999)

        assertNull(loader.load(sourceId, targetId, chapterNumber = 1, unitNumber = null))
    }

    /**
     * An empty book gives the screen nothing to show; returning null means it leaves its existing
     * state alone rather than switching to a target that does not exist.
     */
    @Test
    fun `returns null when the target book has no chapters`() = runTest {
        stubProject(emptyList())

        assertNull(loader.load(sourceId, targetId, chapterNumber = 1, unitNumber = null))
    }
}
