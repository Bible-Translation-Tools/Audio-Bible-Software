package org.bibletranslationtools.otter.common.domain.narration

import kotlinx.coroutines.test.runTest
import org.bibletranslationtools.otter.common.data.workbook.WorkbookBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The teleprompter reads the SOURCE book, not the target — the target is what the user is about to
 * narrate and has no text yet. These pin that, plus the two shapes the caller needs (by verse
 * label for a single verse, in order for the whole chapter).
 */
class LoadChapterSourceTextTest {

    private val loadChapterSourceText = LoadChapterSourceText()

    @Test
    fun `reads the matching source chapter's chunks in both shapes`() = runTest {
        val wb = WorkbookBuilder().build {
            source {
                chapter(1) {
                    verse(1, "In the beginning")
                    verse(2, "And the earth")
                }
                chapter(2) { verse(1, "wrong chapter") }
            }
            // The target carries text of its own, so reading the wrong book would show up.
            target {
                chapter(1) {
                    verse(1, "target one")
                    verse(2, "target two")
                }
            }
        }

        val text = loadChapterSourceText.execute(wb, chapterSort = 1)

        assertEquals(
            mapOf("1" to "In the beginning", "2" to "And the earth"),
            text.byVerseLabel
        )
        assertEquals(listOf("In the beginning", "And the earth"), text.inOrder)
    }

    /** Bridged verses carry a range label like "3-4"; it keys the map exactly as it reads. */
    @Test
    fun `keeps a bridged verse range as its label`() = runTest {
        val wb = WorkbookBuilder().build {
            source { chapter(1) { chunk(3..4, label = "verse", text = "bridged text") } }
        }

        val text = loadChapterSourceText.execute(wb, chapterSort = 1)

        assertEquals(mapOf("3-4" to "bridged text"), text.byVerseLabel)
    }

    /**
     * Matching is by sort, and a source book that is missing the chapter must come back empty
     * rather than silently returning some other chapter's text — narrating verse 1 of chapter 5
     * against chapter 1's script would be worse than an empty teleprompter.
     */
    @Test
    fun `returns empty when the source book has no chapter with that sort`() = runTest {
        val wb = WorkbookBuilder().build {
            source { chapter(1) { verse(1, "chapter one") } }
        }

        val text = loadChapterSourceText.execute(wb, chapterSort = 9)

        assertTrue(text.byVerseLabel.isEmpty())
        assertTrue(text.inOrder.isEmpty())
    }

    @Test
    fun `returns empty for a chapter with no chunks`() = runTest {
        val wb = WorkbookBuilder().build {
            source { chapter(1) }
        }

        val text = loadChapterSourceText.execute(wb, chapterSort = 1)

        assertEquals(LoadChapterSourceText.ChapterSourceText.EMPTY, text)
    }
}
