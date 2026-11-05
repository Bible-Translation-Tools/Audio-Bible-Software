package org.bibletranslationtools.otter.common.domain.content

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.reactivex.Observable
import io.reactivex.Single
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Chunk
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import java.io.IOException
import kotlin.test.Test

class CompileCompletedChaptersTest {

    // Every compile fails, so an error out of execute() means some chapter was compiled.
    private val concatenateAudio: ConcatenateAudio = mockk {
        every { execute(any(), any()) } returns Single.error(IOException("compile failed"))
    }
    private val compile = CompileCompletedChapters(concatenateAudio, mockk(relaxed = true))

    /** A chapter with no chapter take whose one chunk has a take: what gets compiled. */
    private fun compilableChapter(sort: Int): Chapter = mockk {
        every { this@mockk.sort } returns sort
        every { hasSelectedAudio() } returns false
        every { chunks } returns Single.just(
            listOf(mockk<Chunk>(relaxed = true) { every { hasSelectedAudio() } returns true })
        )
    }

    private fun workbookWith(vararg chapters: Chapter): Workbook = mockk {
        every { target.chapters } returns Observable.fromArray(*chapters)
    }

    @Test
    fun `only the chapters being exported are compiled`() {
        val skipped = compilableChapter(1)
        val exported = compilableChapter(2)

        compile.execute(workbookWith(skipped, exported), listOf(2))
            .test().await()
            .assertError(IOException::class.java)

        verify(exactly = 0) { skipped.chunks }
    }
}
