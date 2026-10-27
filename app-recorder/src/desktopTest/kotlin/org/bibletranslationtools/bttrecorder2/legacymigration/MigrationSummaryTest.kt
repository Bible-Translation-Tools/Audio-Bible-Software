package org.bibletranslationtools.bttrecorder2.legacymigration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MigrationSummaryTest {

    private fun moved(location: String, vararg chapters: Int) =
        JournalEntry(location, 0, location, JournalState.VERIFIED, movedChapters = chapters.toList(), imported = true)

    @Test
    fun `a book recorded under two version labels counts once, and so does a chapter both recorded`() {
        val journal = JournalData(
            projects = listOf(
                moved("fr/ulb/psa", 117, 134),
                moved("fr/reg/psa", 117),
                moved("es-419/ulb/psa", 117),
                moved("fr/ulb/2jn", 1)
            ).associateBy { it.key }
        )

        val summary = assertNotNull(MigrationSummaries.pending(journal))

        assertEquals(3, summary.booksMoved, "fr psa, es-419 psa, fr 2jn")
        assertEquals(4, summary.chaptersMoved, "fr psa 117 and 134, es-419 psa 117, fr 2jn 1")
    }
}
