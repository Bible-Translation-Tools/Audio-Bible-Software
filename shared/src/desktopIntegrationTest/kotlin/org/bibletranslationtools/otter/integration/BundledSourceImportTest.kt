package org.bibletranslationtools.otter.integration

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The bundled sources whose structure the versification-first import got wrong, imported for real
 * and checked with the same audit that writes the on-device report.
 *
 * Russian follows Russian Synodal numbering, not the English file the import used to impose, and
 * lost 841 verses of text. Thai's Psalms fit no standard versification and lost 906. English gained
 * empty verses at Acts 19:41 and 2 Cor 13:14.
 */
class BundledSourceImportTest {

    private var env: IntegrationEnvironment? = null

    @AfterTest
    fun tearDown() {
        env?.close()
        env = null
    }

    private fun imported(fileName: String): IntegrationEnvironment =
        IntegrationEnvironment.create().also { env = it }.let { it.import(it.bundledSource(fileName)) }

    private fun assertNothingLost(fileName: String) {
        val findings = imported(fileName).auditSources().single()

        assertEquals(emptyList(), findings.droppedVerses, "$fileName: text with no verse row")
        assertEquals(emptyList(), findings.emptyAfterTextEnd, "$fileName: empty verses after a chapter's text")
    }

    @Test
    fun `Russian keeps every verse of its text`() = assertNothingLost("ru_ulb.zip")

    @Test
    fun `Thai keeps every verse of its text`() = assertNothingLost("th_ulb.zip")

    @Test
    fun `English keeps every verse of its text`() = assertNothingLost("en_ulb.zip")

    /** The bundled English text folds 19:41 into 19:40, so the chapter has 40 verses, all with text. */
    @Test
    fun `English Acts 19 has the 40 verses its text has`() {
        val acts19 = imported("en_ulb.zip").verseCounts("act_19")

        assertEquals(40, acts19.total)
        assertEquals(40, acts19.withText)
    }
}
