package org.bibletranslationtools.bttrecorder2.services

import io.mockk.every
import io.mockk.mockk
import io.reactivex.Single
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Chunk
import org.bibletranslationtools.otter.common.domain.project.exporter.ChapterStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class RecorderChapterCompletionTest {

    private fun chunk(recorded: Boolean): Chunk = mockk { every { hasSelectedAudio() } returns recorded }

    /** A chapter with no take of its own, whose chunks are recorded as given (title first, then verses). */
    private fun chapter(vararg recorded: Boolean): Chapter = mockk {
        every { hasSelectedAudio() } returns false
        every { chunks } returns Single.just(recorded.map { chunk(it) })
    }

    private fun status(chapter: Chapter) = RecorderChapterCompletion().status(mockk(), chapter, ProjectMode.TRANSLATION)

    @Test
    fun `a chapter with every verse recorded but no title recording is not done`() {
        assertEquals(
            ChapterStatus(anyAudioRecorded = true, audioRecorded = false, verseMarkersPlaced = true),
            status(chapter(false, true, true))
        )
    }

    @Test
    fun `a chapter with its title and every verse recorded is done`() {
        assertEquals(
            ChapterStatus(anyAudioRecorded = true, audioRecorded = true, verseMarkersPlaced = true),
            status(chapter(true, true, true))
        )
    }

    @Test
    fun `a chapter with nothing recorded has no audio`() {
        assertEquals(
            ChapterStatus(anyAudioRecorded = false, audioRecorded = false, verseMarkersPlaced = true),
            status(chapter(false, false))
        )
    }
}
