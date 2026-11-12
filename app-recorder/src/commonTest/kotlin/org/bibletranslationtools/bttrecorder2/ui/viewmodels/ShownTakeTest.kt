package org.bibletranslationtools.bttrecorder2.ui.viewmodels

import org.bibletranslationtools.bttrecorder2.ui.MockData
import org.bibletranslationtools.otter.common.data.primitives.MimeType
import org.bibletranslationtools.otter.common.data.workbook.Take
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShownTakeTest {
    private fun take(n: Int) = Take("t$n.wav", File("t$n.wav"), n, MimeType.WAV, LocalDate.of(2026, 1, n))
    private val row = ChapterUiModel(MockData.createMockChapter(1, "1", "1"), takes = listOf(take(1), take(2), take(3)), chapterTakeNumber = 2)

    @Test
    fun `shows the take moved to`() = assertEquals(3, row.shownTake(3)?.number)

    @Test
    fun `otherwise the selected take`() = assertEquals(2, row.shownTake(null)?.number)

    @Test
    fun `a take moved to that is gone falls back to the selected one`() = assertEquals(2, row.shownTake(9)?.number)

    @Test
    fun `with none selected, the newest`() = assertEquals(3, row.copy(chapterTakeNumber = null).shownTake(null)?.number)

    @Test
    fun `no takes, nothing to show`() = assertNull(row.copy(takes = emptyList()).shownTake(null))
}
