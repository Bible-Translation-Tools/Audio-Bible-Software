package org.bibletranslationtools.otter.common.domain.content

import io.mockk.every
import io.mockk.mockk
import io.reactivex.Single
import kotlinx.coroutines.test.runTest
import org.bibletranslationtools.otter.common.data.primitives.CheckingStatus
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.otter.common.data.workbook.SaveResult
import org.bibletranslationtools.otter.common.data.workbook.TakeCheckingState
import org.bibletranslationtools.otter.common.data.workbook.FakeWorkbookStore
import org.bibletranslationtools.otter.common.data.workbook.WorkbookBuilder
import org.bibletranslationtools.otter.common.utils.computeFileChecksum
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChapterTranslationBuilderTest {

    private fun tempFile(bytes: ByteArray) =
        File.createTempFile("chapter", ".wav").apply { writeBytes(bytes); deleteOnExit() }

    /**
     * Compiling a chapter whose chunk audio has not changed returns the chapter take it already
     * has. It must not add that take to the chapter a second time.
     */
    @Test
    fun `compiling an unchanged chapter does not add its take again`() = runTest {
        val compiled = tempFile(byteArrayOf(1, 2, 3))
        val store = FakeWorkbookStore()
        val workbook = WorkbookBuilder(store).build {
            target { chapter(1) { chunk(1..1) { take("chunk_t1.wav", selected = true) } } }
        }
        val chapter = workbook.target.chapters().single()
        val existing = assertIs<SaveResult.Saved<*>>(
            chapter.audio.insertTake(
                TakeFiles.Reserved(1, tempFile(byteArrayOf(1, 2, 3))).toTake()
                    .copy(checking = TakeCheckingState(CheckingStatus.VERSE, computeFileChecksum(compiled))),
                select = true
            )
        ).value
        store.calls.clear()

        val concatenate = mockk<ConcatenateAudio>()
        every { concatenate.execute(any(), includeMarkers = false) } answers { Single.just(tempFile(byteArrayOf(1, 2, 3))) }
        val builder = ChapterTranslationBuilder(concatenate, TakeCreator(mockk(relaxed = true)))

        val result = builder.getOrCompile(workbook, chapter)

        assertEquals(existing, assertIs<SaveResult.Saved<*>>(result).value)
        assertEquals(1, chapter.audio.getAllTakes().size, "the chapter's take was added a second time")
        assertEquals(1, store.saved(chapter.audio.owner).takes.size, "the chapter's take was saved a second time")
        assertTrue("addTake" !in store.calls, "no take should have been written, calls were ${store.calls}")
    }
}
