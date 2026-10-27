package org.bibletranslationtools.otter.common.domain.resourcecontainer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceStructureAuditTest {

    private fun rows(vararg verses: Int) = verses.map { StoredVerseRow(it, it) }

    private fun audit(text: Map<String, Set<Int>>, stored: Map<String, List<StoredVerseRow>>) =
        auditSourceStructure("ulb", "en", "12", "/rc", text, stored)

    @Test
    fun `text with no stored row is reported as dropped`() {
        val findings = audit(mapOf("num_13" to setOf(1, 2, 3)), mapOf("num_13" to rows(1, 2)))

        assertEquals(listOf("num_13:3"), findings.droppedVerses)
        assertTrue(findings.needsRepair)
    }

    @Test
    fun `an empty row after the text's last verse is reported`() {
        val findings = audit(mapOf("act_19" to (1..40).toSet()), mapOf("act_19" to rows(*(1..41).toList().toIntArray())))

        assertEquals(listOf("act_19:41"), findings.emptyAfterTextEnd)
        assertTrue(findings.needsRepair)
    }

    @Test
    fun `an empty row inside a chapter is expected, not a repair`() {
        val findings = audit(mapOf("mrk_7" to setOf(15, 17)), mapOf("mrk_7" to rows(15, 16, 17)))

        assertEquals(listOf("mrk_7:16"), findings.emptyInsideChapter)
        assertFalse(findings.needsRepair)
    }

    @Test
    fun `bridged rows cover every verse in the bridge`() {
        val findings = audit(mapOf("jhn_1" to setOf(1, 2, 3)), mapOf("jhn_1" to listOf(StoredVerseRow(1, 3))))

        assertEquals(emptyList(), findings.droppedVerses)
        assertEquals(emptyList(), findings.emptyAfterTextEnd)
    }

    @Test
    fun `chapters with no text are counted as templates`() {
        val findings = audit(emptyMap(), mapOf("gen_1" to rows(1, 2), "gen_2" to rows(1)))

        assertEquals(2, findings.templateChapters)
        assertFalse(findings.needsRepair)
    }
}
