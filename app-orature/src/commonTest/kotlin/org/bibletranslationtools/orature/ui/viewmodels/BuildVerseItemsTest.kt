package org.bibletranslationtools.orature.ui.viewmodels

import org.bibletranslationtools.otter.common.data.audio.ChapterMarker
import org.bibletranslationtools.otter.common.data.audio.VerseMarker
import org.bibletranslationtools.otter.common.domain.narration.teleprompter.NarratableItem
import org.bibletranslationtools.otter.common.domain.narration.teleprompter.TeleprompterItemState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuildVerseItemsTest {

    @Test
    fun `maps markers, per-verse state, text, and the title flag`() {
        val markers = listOf(
            ChapterMarker(1, 0),        // title marker
            VerseMarker(1, 1, 100),     // label "1"
            VerseMarker(2, 3, 200)      // bridged label "2-3"
        )
        val items = listOf(
            NarratableItem(TeleprompterItemState.RECORD_DISABLED, false, false, false),
            NarratableItem(TeleprompterItemState.RECORD_AGAIN, isPlayOptionEnabled = true, isEditVerseOptionEnabled = true, isRecordAgainOptionEnabled = true),
            NarratableItem(TeleprompterItemState.RECORD, false, false, false)
        )
        val byLabel = mapOf("1" to "In the beginning", "2-3" to "And God said")
        // Parallel-to-markers source text (index 0 = the chapter/book title chunk).
        val byIndex = listOf("Genesis", "In the beginning", "And God said")

        val result = buildVerseItems(markers, items, byLabel, byIndex)

        assertEquals(3, result.size)

        // Title marker (chapter/book) → recordable row that shows the source title text (by
        // index), not verse text (its label can collide with a verse label).
        assertTrue(result[0].isTitle)
        assertEquals("1", result[0].label)
        assertEquals("Genesis", result[0].text)

        // Verse 1 → text + RECORD_AGAIN state + enabled flags carried through.
        assertFalse(result[1].isTitle)
        assertEquals("1", result[1].label)
        assertEquals("In the beginning", result[1].text)
        assertEquals(TeleprompterItemState.RECORD_AGAIN, result[1].state)
        assertTrue(result[1].isPlayEnabled)
        assertTrue(result[1].isEditEnabled)
        assertTrue(result[1].isRecordAgainEnabled)

        // Bridged verse label maps to its text.
        assertEquals("2-3", result[2].label)
        assertEquals("And God said", result[2].text)
        assertEquals(TeleprompterItemState.RECORD, result[2].state)
    }

    @Test
    fun `defaults to RECORD_DISABLED when there are fewer states than markers`() {
        val markers = listOf(VerseMarker(1, 1, 0), VerseMarker(2, 2, 10))
        val items = listOf(NarratableItem(TeleprompterItemState.RECORD, false, false, false))

        val result = buildVerseItems(markers, items, emptyMap(), emptyList())

        assertEquals(TeleprompterItemState.RECORD, result[0].state)
        assertEquals(TeleprompterItemState.RECORD_DISABLED, result[1].state)
        assertFalse(result[1].isPlayEnabled)
    }
}
