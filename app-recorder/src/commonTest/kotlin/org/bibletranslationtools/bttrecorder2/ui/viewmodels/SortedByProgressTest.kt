package org.bibletranslationtools.bttrecorder2.ui.viewmodels

import org.bibletranslationtools.bttrecorder2.ui.MockData
import kotlin.test.Test
import kotlin.test.assertEquals

class SortedByProgressTest {
    // Bible order 1..4 under ids 10..40; 20 and 30 tie at 0.5, 40 has no progress yet.
    private val books = (1..4).map { n ->
        val wb = MockData.createMockWorkbook(n * 10, "b$n", "Book $n", MockData.englishLanguage)
        wb.copy(sourceCollection = wb.sourceCollection.copy(sort = n))
    }
    private val progress = mapOf(10 to 1.0, 20 to 0.5, 30 to 0.5)

    @Test
    fun `ascending puts least done first, ties in Bible order`() {
        assertEquals(listOf(40, 20, 30, 10), books.sortedByProgress(progress, SortDirection.ASC).map { it.id })
    }

    @Test
    fun `descending puts most done first, ties still in Bible order`() {
        assertEquals(listOf(10, 20, 30, 40), books.sortedByProgress(progress, SortDirection.DESC).map { it.id })
    }
}
