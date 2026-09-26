package org.bibletranslationtools.otter.common.domain.resourcecontainer.structure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins how [StructuralDiff] serves a reference beside a project (step 12, design only): another
 * language's edition, or another versification's, lined up with the project's verses. See
 * docs/source-reference-design.md. These cases need no change to the diff: the caller decides
 * what text to pass and what numbering to pass it in.
 */
class StructuralDiffReferenceTest {

    private fun v(start: Int, text: String?, end: Int = start) = VerseText(VerseRange(start, end), text)

    private fun chapter(book: String, number: Int, verses: List<VerseText>) =
        ChapterText("${book}_$number", book, number, verses)

    private fun edition(vararg chapters: ChapterText) = EditionText(chapters.associateBy { it.slug })

    private val english = chapter(
        "act", 19, listOf(
            v(39, "But if you are seeking anything more, it should be resolved in the regular assembly."),
            v(40, "For we are in danger of being accused of rioting today, and there is no cause we can give to justify this uproar."),
            v(41, "When he had said this, he dismissed the assembly.")
        )
    )

    private val french = chapter(
        "act", 19, listOf(
            v(39, "Et si vous avez en vue d'autres objets, ils se régleront dans une assemblée légale."),
            v(40, "Nous risquons en effet d'être accusés de sédition pour ce qui s'est passé aujourd'hui."),
            v(41, "Après ces paroles, il congédia l'assemblée.")
        )
    )

    private fun textless(chapter: ChapterText) = chapter.copy(verses = chapter.verses.map { it.copy(text = null) })

    @Test
    fun `another language's edition, passed without its text, pairs verse by verse by number`() {
        val result = StructuralDiff.compare(edition(english), edition(textless(french))).chapters.getValue("act_19")

        assertTrue(result.isIdentical)
        assertEquals(listOf(39, 40, 41), result.groups.map { it.to.single().start })
    }

    @Test
    fun `another language's text, passed as it is, still pairs by number and is only reworded`() {
        val result = StructuralDiff.compare(edition(english), edition(french)).chapters.getValue("act_19")

        // Nothing is taken for a move or a fold: text in another language never looks alike enough.
        assertTrue(result.isTextOnly)
        assertEquals(result.groups.map { it.from }, result.groups.map { it.to })
    }

    /** English (`eng`) Malachi 4:1-6 is `org` 3:19-24; the Copenhagen `mappedVerses` say so. */
    private val malachiEng = listOf(
        chapter("mal", 3, (1..18).map { v(it, null) }),
        chapter("mal", 4, (1..6).map { v(it, null) })
    )
    private val malachiOrg = chapter("mal", 3, (1..24).map { v(it, null) })

    /** What the caller does with `eng.json`'s `"MAL 4:1-6": "MAL 3:19-24"` before comparing. */
    private fun engToOrg(chapters: List<ChapterText>): EditionText {
        val (three, four) = chapters
        return edition(three.copy(verses = three.verses + four.verses.map { it.copy(range = VerseRange(it.range.start + 18)) }))
    }

    @Test
    fun `a reference in another versification lines up once both sides are numbered in org`() {
        val result = StructuralDiff.compare(engToOrg(malachiEng), edition(malachiOrg))

        assertTrue(result.chapters.values.all { it.isIdentical })
        val nineteen = result.chapters.getValue("mal_3").groups.single { it.from.single().start == 19 }
        assertEquals(19, nineteen.to.single().start, "English 4:1, as org 3:19")
    }

    @Test
    fun `without the mapping, textless editions can't see the move`() {
        val result = StructuralDiff.compare(edition(*malachiEng.toTypedArray()), edition(malachiOrg))

        // Why the mapping comes first: text is what finds a move, and a reference in another
        // language has none to offer.
        assertEquals(setOf(VerseChange.REMOVED), result.chapters.getValue("mal_4").changes)
        assertEquals(setOf(VerseChange.ADDED), result.chapters.getValue("mal_3").changes)
    }
}
