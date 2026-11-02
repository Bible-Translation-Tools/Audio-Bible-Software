package org.bibletranslationtools.bttrecorder2.ui.viewmodels

import org.bibletranslationtools.bttrecorder2.ui.MockData
import org.bibletranslationtools.otter.common.data.primitives.MimeType
import org.bibletranslationtools.otter.common.data.workbook.Take
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class ChapterCompletionTest {
    private val row = ChapterUiModel(MockData.createMockChapter(1, "1", "1"), progress = 0.25f)
    private val take = Take("c.wav", File("c.wav"), 1, MimeType.WAV, LocalDate.of(2026, 1, 1))

    @Test
    fun `verses only count their recorded share`() = assertEquals(0.25f, row.completion)

    @Test
    fun `a selected chapter take makes it done`() = assertEquals(1f, row.copy(hasChapterTake = true).completion)

    @Test
    fun `any chapter take makes it done, selected or not`() = assertEquals(1f, row.copy(takes = listOf(take)).completion)
}
