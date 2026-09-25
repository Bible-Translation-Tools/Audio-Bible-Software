package org.bibletranslationtools.otter.common.domain.versification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VersificationDetectorTest {

    private class FakeVersification(private val books: Map<String, List<Int>>) : Versification {
        override fun getBookSlugs(): List<String> = books.keys.toList()
        override fun getChaptersInBook(bookSlug: String): Int = books[bookSlug]?.size ?: 0
        override fun getVersesInChapter(bookSlug: String, chapterNumber: Int): Int =
            books[bookSlug]?.getOrNull(chapterNumber - 1) ?: 0
    }

    private val eng = "eng" to FakeVersification(mapOf("psa" to listOf(6, 12, 8), "mal" to listOf(14, 17, 18, 6)))
    private val org = "org" to FakeVersification(mapOf("psa" to listOf(6, 12, 9), "mal" to listOf(14, 17, 24)))

    private fun text(vararg books: Pair<String, List<Int>>) = TextStructure(
        books.associate { (book, verses) -> book to verses.withIndex().associate { (i, v) -> i + 1 to v } }
    )

    @Test
    fun `picks the candidate with the fewest differing chapters`() {
        val match = VersificationDetector.detect(text("psa" to listOf(6, 12, 9)), listOf(eng, org))

        assertEquals("org", match?.code)
        assertEquals(emptyList(), match?.differingChapters)
    }

    @Test
    fun `reports the chapters that differ`() {
        val match = VersificationDetector.detect(text("psa" to listOf(6, 12, 9), "mal" to listOf(14, 17, 18, 6)), listOf(eng, org))

        assertEquals("eng", match?.code, "Malachi's extra chapter outweighs one Psalm")
        assertEquals(listOf("psa 3"), match?.differingChapters)
    }

    @Test
    fun `a chapter one side lacks counts as differing`() {
        val match = VersificationDetector.detect(text("mal" to listOf(14, 17, 24)), listOf(eng))

        assertEquals(listOf("mal 3", "mal 4"), match?.differingChapters)
    }

    @Test
    fun `ties go to the earlier candidate`() {
        val match = VersificationDetector.detect(text("psa" to listOf(6)), listOf(org, eng))

        assertEquals("org", match?.code)
    }

    @Test
    fun `no candidates means no match`() {
        assertNull(VersificationDetector.detect(text("psa" to listOf(6)), emptyList()))
    }

    /** The bundled `ulb` file uses `gen`; Copenhagen files use `GEN`. */
    @Test
    fun `Paratext versifications are looked up case-insensitively`() {
        val versification = ParatextVersification(
            basedOn = null,
            maxVerses = mapOf("GEN" to listOf("31", "25")),
            mappedVerses = null,
            excludedVerses = null,
            partialVerses = null
        )

        assertEquals(2, versification.getChaptersInBook("gen"))
        assertEquals(25, versification.getVersesInChapter("gen", 2))
        assertEquals(0, versification.getVersesInChapter("gen", 3), "a chapter past the end is 0, not an error")
        assertEquals(listOf("gen"), versification.getBookSlugs())
    }
}
