package org.bibletranslationtools.otter.common.domain.content

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.reactivex.Observable
import io.reactivex.Single
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Chunk
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import java.io.File
import java.io.IOException
import kotlin.test.Test

class CompileCompletedChaptersTest {

    private val concatenateAudio: ConcatenateAudio = mockk()
    private val saveAudioAsNewTake: SaveAudioAsNewTake = mockk()
    private val compile = CompileCompletedChapters(concatenateAudio, saveAudioAsNewTake)

    /** A chapter with no chapter take whose one chunk (verse 1) has a take: what gets compiled. */
    private fun compilableChapter(sort: Int): Chapter = mockk {
        every { this@mockk.sort } returns sort
        every { hasSelectedAudio() } returns false
        every { chunks } returns Single.just(
            listOf(
                mockk<Chunk>(relaxed = true) {
                    every { hasSelectedAudio() } returns true
                    every { start } returns 1
                    every { end } returns 1
                    every { audio.getSelectedTake()!!.file } returns wav()
                }
            )
        )
    }

    private fun workbookWith(vararg chapters: Chapter): Workbook = mockk {
        every { target.chapters } returns Observable.fromArray(*chapters)
    }

    @Test
    fun `only the chapters being exported are compiled`() {
        // Every compile fails, so an error out of execute() means some chapter was compiled.
        every { concatenateAudio.execute(any(), any()) } returns Single.error(IOException("compile failed"))
        val skipped = compilableChapter(1)
        val exported = compilableChapter(2)

        compile.execute(workbookWith(skipped, exported), listOf(2))
            .test().await()
            .assertError(IOException::class.java)

        verify(exactly = 0) { skipped.chunks }
    }

    /**
     * The repository selects a saved take only once its insert finishes, so the exporter cannot read
     * the selection right after compiling; it uses the takes returned here instead.
     */
    @Test
    fun `returns each compiled take by chapter`() {
        every { concatenateAudio.execute(any(), any()) } answers { Single.just(wav()) }
        val saved: Take = mockk()
        coEvery { saveAudioAsNewTake.execute(any(), any(), any(), any(), any()) } returns saved

        compile.execute(workbookWith(compilableChapter(3)), null)
            .test().await()
            .assertValue(mapOf(3 to saved))
    }

    private fun wav(): File {
        val file = File.createTempFile("compile-chapters", ".wav").apply { deleteOnExit() }
        OratureAudioFile(file, 1, 44_100, 16).writer().use { it.write(ByteArray(2_000)) }
        return file
    }
}
