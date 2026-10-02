package org.bibletranslationtools.otter.integration

import org.bibletranslationtools.otter.common.domain.resourcecontainer.structure.StructuralDiff
import org.bibletranslationtools.otter.common.domain.resourcecontainer.structure.VerseChange
import org.bibletranslationtools.otter.common.domain.resourcecontainer.structure.VerseRange
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The structural diff on whole real editions of the English ULB. */
class StructuralDiffEditionsTest {

    private var env: IntegrationEnvironment? = null

    @AfterTest
    fun tearDown() {
        env?.close()
        env = null
    }

    private fun environment() = IntegrationEnvironment.create().also { env = it }

    /**
     * The two zips that both say `version: '12'`: the bundled one folds Acts 19:41 into 19:40 and
     * 2 Cor 13:14 into 13:13, and rewords a good deal besides.
     */
    @Test
    fun `the two v12 zips differ in structure in exactly Acts 19 and 2 Corinthians 13`() {
        val environment = environment()
        val fixture = environment.editionText(environment.fixture("en_ulb.zip"))
        val bundled = environment.editionText(environment.bundledSource("en_ulb.zip"))

        val diff = StructuralDiff.compare(fixture, bundled)

        assertEquals(listOf("2co_13", "act_19"), diff.structurallyChanged.map { it.chapterSlug }.sorted())

        val acts19 = diff.chapters.getValue("act_19")
        assertEquals(setOf(VerseChange.MERGED), acts19.changes - VerseChange.TEXT_CHANGED)
        val fold = acts19.groups.single { it.change == VerseChange.MERGED }
        assertEquals(listOf(VerseRange(40), VerseRange(41)), fold.from)
        assertEquals(listOf(VerseRange(40)), fold.to)

        // 13:13 ("All the saints greet you") folds into 13:12, and 13:14 ("The grace…") becomes 13:13.
        val cor13 = diff.chapters.getValue("2co_13")
        assertEquals(setOf(VerseChange.MERGED, VerseChange.RENUMBERED), cor13.changes - VerseChange.TEXT_CHANGED)
        val merged = cor13.groups.single { it.change == VerseChange.MERGED }
        assertEquals(listOf(VerseRange(12), VerseRange(13)), merged.from)
        assertEquals(listOf(VerseRange(12)), merged.to)
        val renumbered = cor13.groups.single { it.change == VerseChange.RENUMBERED }
        assertEquals(listOf(VerseRange(14)), renumbered.from)
        assertEquals(listOf(VerseRange(13)), renumbered.to)
        assertTrue(diff.chapters.values.count { it.isTextOnly } > 0, "the bundled zip also rewords verses")
    }

    @Test
    fun `a reworded verse is the only change`() {
        val environment = environment()
        val fixture = environment.editionText(environment.fixture("en_ulb.zip"))
        val reworded = environment.editionText(environment.newerEdition("en_ulb.zip"))

        val diff = StructuralDiff.compare(fixture, reworded)

        val changed = diff.chapters.values.filter { !it.isIdentical }
        assertEquals(listOf("jud_1"), changed.map { it.chapterSlug })
        assertTrue(changed.single().isTextOnly)
        assertEquals(listOf(VerseRange(1)), changed.single().textChangedVerses)
    }

    @Test
    fun `a whole Bible compares quickly`() {
        val environment = environment()
        val fixture = environment.editionText(environment.fixture("en_ulb.zip"))
        val bundled = environment.editionText(environment.bundledSource("en_ulb.zip"))

        val started = System.nanoTime()
        StructuralDiff.compare(fixture, bundled)
        val millis = (System.nanoTime() - started) / 1_000_000

        assertTrue(millis < 5_000, "comparing two Bibles took $millis ms")
    }
}
