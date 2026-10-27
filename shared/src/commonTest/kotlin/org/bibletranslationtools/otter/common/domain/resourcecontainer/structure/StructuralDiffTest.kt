package org.bibletranslationtools.otter.common.domain.resourcecontainer.structure

import org.bibletranslationtools.otter.common.domain.resourcecontainer.structure.VerseChange.ADDED
import org.bibletranslationtools.otter.common.domain.resourcecontainer.structure.VerseChange.IDENTICAL
import org.bibletranslationtools.otter.common.domain.resourcecontainer.structure.VerseChange.MERGED
import org.bibletranslationtools.otter.common.domain.resourcecontainer.structure.VerseChange.MOVED_IN
import org.bibletranslationtools.otter.common.domain.resourcecontainer.structure.VerseChange.MOVED_OUT
import org.bibletranslationtools.otter.common.domain.resourcecontainer.structure.VerseChange.REGROUPED
import org.bibletranslationtools.otter.common.domain.resourcecontainer.structure.VerseChange.REMOVED
import org.bibletranslationtools.otter.common.domain.resourcecontainer.structure.VerseChange.RENUMBERED
import org.bibletranslationtools.otter.common.domain.resourcecontainer.structure.VerseChange.SPLIT
import org.bibletranslationtools.otter.common.domain.resourcecontainer.structure.VerseChange.TEXT_CHANGED
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StructuralDiffTest {

    private fun v(start: Int, text: String?, end: Int = start) = VerseText(VerseRange(start, end), text)

    private fun chapter(book: String, number: Int, vararg verses: VerseText) =
        ChapterText("${book}_$number", book, number, verses.toList())

    private fun edition(vararg chapters: ChapterText) = EditionText(chapters.associateBy { it.slug })

    private fun diff(from: EditionText, to: EditionText) = StructuralDiff.compare(from, to)

    private fun ChapterDiff.group(change: VerseChange) = groups.single { it.change == change }

    private val acts19old = chapter(
        "act", 19,
        v(39, "But if you are seeking anything more, it should be resolved in the regular assembly."),
        v(40, "For we are in danger of being accused of rioting today, and there is no cause we can give to justify this uproar."),
        v(41, "When he had said this, he dismissed the assembly.")
    )

    @Test
    fun `the same chapter is identical`() {
        val result = diff(edition(acts19old), edition(acts19old)).chapters.getValue("act_19")

        assertTrue(result.isIdentical)
        assertEquals(3, result.groups.count { it.change == IDENTICAL })
    }

    @Test
    fun `reworded verses are a text-only change, listed per verse`() {
        val reworded = acts19old.copy(verses = acts19old.verses.map {
            if (it.range.start == 39) it.copy(text = "But if you want anything more, it will be settled in the lawful assembly.") else it
        })

        val result = diff(edition(acts19old), edition(reworded)).chapters.getValue("act_19")

        assertTrue(result.isTextOnly)
        assertEquals(listOf(VerseRange(39)), result.textChangedVerses)
    }

    @Test
    fun `punctuation and spacing alone aren't a text change`() {
        val respaced = acts19old.copy(verses = acts19old.verses.map { it.copy(text = it.text?.replace(",", "")?.uppercase() + "  ") })

        assertTrue(diff(edition(acts19old), edition(respaced)).chapters.getValue("act_19").isIdentical)
    }

    /** English ULB: 19:41 folded into 19:40, with no bridge. Numbers alone would call it removed. */
    @Test
    fun `a verse folded into its neighbour is a merge`() {
        val folded = chapter(
            "act", 19,
            acts19old.verses[0],
            v(40, "For we are in danger of being accused of rioting today, and there is no cause we can give to justify this uproar.\" When he had said this, he dismissed the assembly.")
        )

        val result = diff(edition(acts19old), edition(folded)).chapters.getValue("act_19")

        assertEquals(setOf(MERGED), result.changes)
        assertEquals(listOf(VerseRange(40), VerseRange(41)), result.group(MERGED).from)
        assertEquals(listOf(VerseRange(40)), result.group(MERGED).to)
    }

    @Test
    fun `two verses bridged is a merge`() {
        val bridged = chapter("act", 19, acts19old.verses[0], v(40, "both verses", end = 41))

        val result = diff(edition(acts19old), edition(bridged)).chapters.getValue("act_19")

        assertEquals(listOf(VerseRange(40, 41)), result.group(MERGED).to)
    }

    @Test
    fun `a bridge undone is a split`() {
        val bridged = chapter("act", 19, v(40, "both verses", end = 41))
        val separate = chapter("act", 19, v(40, "first"), v(41, "second"))

        val result = diff(edition(bridged), edition(separate)).chapters.getValue("act_19")

        assertEquals(setOf(SPLIT), result.changes)
        assertEquals(listOf(VerseRange(40), VerseRange(41)), result.group(SPLIT).to)
    }

    /** The reverse of the fold: part of verse 40 given its own number. */
    @Test
    fun `a verse cut out of its neighbour is a split`() {
        val folded = chapter("act", 19, v(40, acts19old.verses[1].text + " " + acts19old.verses[2].text))

        val result = diff(edition(folded), edition(acts19old.copy(verses = acts19old.verses.drop(1)))).chapters.getValue("act_19")

        assertEquals(setOf(SPLIT), result.changes)
    }

    @Test
    fun `bridges regrouped are a regroup`() {
        val a = chapter("jhn", 1, v(1, "x", end = 2), v(3, "y"))
        val b = chapter("jhn", 1, v(1, "x"), v(2, "y", end = 3))

        assertEquals(setOf(REGROUPED), diff(edition(a), edition(b)).chapters.getValue("jhn_1").changes)
    }

    @Test
    fun `a verse with unrelated text and no number in common is added or removed`() {
        val withVariant = chapter("mrk", 7, v(15, "Nothing outside can defile"), v(16, "If anyone has ears to hear, let him hear."), v(17, "When he had gone indoors"))
        val without = chapter("mrk", 7, v(15, "Nothing outside can defile"), v(17, "When he had gone indoors"))

        assertEquals(setOf(REMOVED), diff(edition(withVariant), edition(without)).chapters.getValue("mrk_7").changes)
        assertEquals(setOf(ADDED), diff(edition(without), edition(withVariant)).chapters.getValue("mrk_7").changes)
    }

    @Test
    fun `the end of one chapter moving to the start of the next is a move`() {
        val old = edition(
            chapter("isa", 8, v(21, "They will pass through the land"), v(22, "They will look to the earth and see distress and darkness")),
            chapter("isa", 9, v(1, "But there will be no more gloom"))
        )
        val new = edition(
            chapter("isa", 8, v(21, "They will pass through the land")),
            chapter("isa", 9, v(1, "They will look to the earth and see distress and darkness"), v(2, "But there will be no more gloom"))
        )

        val result = diff(old, new)

        val out = result.chapters.getValue("isa_8").group(MOVED_OUT)
        assertEquals(VerseLocation("isa_9", VerseRange(1)), out.movedTo)
        val into = result.chapters.getValue("isa_9").group(MOVED_IN)
        assertEquals(VerseLocation("isa_8", VerseRange(22)), into.movedFrom)
        // Everything after it in chapter 9 shifts by one.
        val shifted = result.chapters.getValue("isa_9").group(RENUMBERED)
        assertEquals(listOf(VerseRange(1)), shifted.from)
        assertEquals(listOf(VerseRange(2)), shifted.to)
    }

    /** eng Malachi 4:1-6 is org Malachi 3:19-24: a whole chapter renumbered into another. */
    @Test
    fun `a chapter renumbered into another is moves`() {
        val texts = listOf("For behold the day comes", "But for you who fear my name", "You will trample the wicked",
            "Remember the law of Moses", "See I will send you Elijah", "He will turn the hearts of fathers")
        val eng = edition(
            chapter("mal", 3, v(18, "Then you will again distinguish")),
            chapter("mal", 4, *texts.mapIndexed { i, t -> v(i + 1, t) }.toTypedArray())
        )
        val org = edition(
            chapter("mal", 3, v(18, "Then you will again distinguish"), *texts.mapIndexed { i, t -> v(19 + i, t) }.toTypedArray())
        )

        val result = diff(eng, org)

        assertEquals(setOf(MOVED_OUT), result.chapters.getValue("mal_4").changes)
        assertEquals((19..24).map { VerseRange(it) }, result.chapters.getValue("mal_3").groups.filter { it.change == MOVED_IN }.flatMap { it.to })
    }

    /** Thai has no spaces between words; text is compared by character pairs, not words. */
    @Test
    fun `text without spaces is still matched`() {
        val old = chapter("act", 19, v(40, "เพราะเราเสี่ยงที่จะถูกกล่าวหา"), v(41, "เมื่อท่านพูดอย่างนั้นแล้วก็ให้ที่ประชุมเลิก"))
        val new = chapter("act", 19, v(40, "เพราะเราเสี่ยงที่จะถูกกล่าวหา เมื่อท่านพูดอย่างนั้นแล้วก็ให้ที่ประชุมเลิก"))

        assertEquals(setOf(MERGED), diff(edition(old), edition(new)).chapters.getValue("act_19").changes)
    }

    @Test
    fun `verses without text are matched by number only`() {
        val old = chapter("act", 19, v(40, null), v(41, null))
        val new = chapter("act", 19, v(40, null))

        assertEquals(setOf(REMOVED), diff(edition(old), edition(new)).chapters.getValue("act_19").changes)
    }

    @Test
    fun `structurally changed chapters exclude text-only ones`() {
        val reworded = chapter("act", 18, v(1, "After these things"))
        val result = diff(
            edition(chapter("act", 18, v(1, "After this")), acts19old),
            edition(reworded, chapter("act", 19, acts19old.verses[0], v(40, "both", end = 41)))
        )

        assertEquals(listOf("act_19"), result.structurallyChanged.map { it.chapterSlug })
    }
}
