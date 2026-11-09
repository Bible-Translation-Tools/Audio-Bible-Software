package org.bibletranslationtools.otter.common.domain.wacs.layout

import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.domain.content.FileNamer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WacsRepoLayoutTest {

    private fun namer(book: String, chapter: Int, chapters: Long, rcSlug: String = "ulb") = FileNamer(
        contentType = ContentType.META,
        languageSlug = "en",
        bookSlug = book,
        rcSlug = rcSlug,
        chunkCount = 0,
        chapterCount = chapters,
        chapterTitle = "$chapter",
        chapterSort = chapter,
    )

    @Test
    fun `a published chapter is named like its takes, without the take`() {
        assertEquals("media/mrk/en_ulb_mrk_c01.wav", WacsRepoLayout.chapterFilePath("MRK", namer("mrk", 1, 16).chapterFileName("wav")))
        assertEquals("media/psa/en_ulb_psa_c119.json", WacsRepoLayout.chapterFilePath("PSA", namer("psa", 119, 150).chapterFileName("json")))
    }

    @Test
    fun `each chapter has its own branch, padded like its file names`() {
        assertEquals("publish/mrk-c01", WacsRepoLayout.chapterBranch("MRK", 1))
        assertEquals("publish/mrk-c16", WacsRepoLayout.chapterBranch("mrk", 16))
        assertEquals("publish/psa-c001", WacsRepoLayout.chapterBranch("PSA", 1))
        assertEquals("publish/psa-c119", WacsRepoLayout.chapterBranch("psa", 119))
    }

    @Test
    fun `a chapter's files are found whatever language and edition their names carry`() {
        assertTrue(WacsRepoLayout.isChapterFile("media/mrk/en_ulb_mrk_c01.wav", "MRK", 1))
        assertTrue(WacsRepoLayout.isChapterFile("media/mrk/es-419_reg_mrk_c01.json", "MRK", 1))
        assertTrue(WacsRepoLayout.isChapterFile("media/psa/en_ulb_psa_c119.flac", "PSA", 119))
    }

    @Test
    fun `another chapter's or book's files, or the old layout, aren't the chapter's`() {
        assertFalse(WacsRepoLayout.isChapterFile("media/mrk/en_ulb_mrk_c10.wav", "MRK", 1))
        assertFalse(WacsRepoLayout.isChapterFile("media/mrk/en_ulb_mrk_c011.wav", "MRK", 1))
        assertFalse(WacsRepoLayout.isChapterFile("media/luk/en_ulb_mrk_c01.wav", "MRK", 1))
        assertFalse(WacsRepoLayout.isChapterFile("media/mrk/en_ulb_mrk_c01.mp3", "MRK", 1))
        assertFalse(WacsRepoLayout.isChapterFile("ingredients/MRK/01.wav", "MRK", 1))
    }
}
