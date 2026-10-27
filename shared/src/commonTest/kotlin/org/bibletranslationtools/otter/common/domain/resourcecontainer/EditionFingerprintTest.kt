package org.bibletranslationtools.otter.common.domain.resourcecontainer

import org.bibletranslationtools.otter.common.collections.OtterTree
import org.bibletranslationtools.otter.common.collections.OtterTreeNode
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.CollectionOrContent
import org.bibletranslationtools.otter.common.data.primitives.Content
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EditionFingerprintTest {

    private fun collection(slug: String) =
        Collection(sort = 1, slug = slug, labelKey = "chapter", titleKey = slug, resourceContainer = null)

    private fun content(start: Int, end: Int = start, text: String = "verse $start", type: ContentType = ContentType.TEXT) =
        Content(
            sort = start, labelKey = "verse", start = start, end = end, selectedTake = null,
            text = text, format = "text/usfm", type = type, draftNumber = 1
        )

    private fun chapter(slug: String, vararg contents: Content) =
        OtterTree<CollectionOrContent>(collection(slug)).apply { contents.forEach { addChild(OtterTreeNode(it)) } }

    private fun source(vararg chapters: OtterTree<CollectionOrContent>) =
        OtterTree<CollectionOrContent>(collection("root")).apply {
            addChild(OtterTree<CollectionOrContent>(collection("act")).apply { chapters.forEach(::addChild) })
        }

    private fun fingerprint(vararg chapters: OtterTree<CollectionOrContent>) =
        EditionFingerprint.of(source(*chapters), detectedVersification = "eng")

    private fun EditionFingerprint.chapter(slug: String) = chapters.single { it.chapterSlug == slug }

    @Test
    fun `the same text gives the same fingerprint`() {
        val a = fingerprint(chapter("act_19", content(40), content(41)))
        val b = fingerprint(chapter("act_19", content(40), content(41)))

        assertEquals(a, b)
    }

    /** The English ULB change: 19:41 folded into 19:40. */
    @Test
    fun `a merged verse changes the structure of that chapter only`() {
        val before = fingerprint(chapter("act_18", content(1)), chapter("act_19", content(40), content(41)))
        val after = fingerprint(chapter("act_18", content(1)), chapter("act_19", content(40)))

        assertNotEquals(before.structureFingerprint, after.structureFingerprint)
        assertEquals(before.chapter("act_18"), after.chapter("act_18"))
        assertNotEquals(before.chapter("act_19").structureHash, after.chapter("act_19").structureHash)
    }

    @Test
    fun `a bridge is a different structure from separate verses`() {
        val separate = fingerprint(chapter("act_1", content(1), content(2)))
        val bridged = fingerprint(chapter("act_1", content(1, 2)))

        assertNotEquals(separate.structureFingerprint, bridged.structureFingerprint)
    }

    @Test
    fun `a wording change leaves the structure and changes the text`() {
        val before = fingerprint(chapter("act_1", content(1, text = "In the beginning")))
        val after = fingerprint(chapter("act_1", content(1, text = "At the beginning")))

        assertEquals(before.structureFingerprint, after.structureFingerprint)
        assertNotEquals(before.textFingerprint, after.textFingerprint)
    }

    @Test
    fun `whitespace differences don't change the text`() {
        val a = fingerprint(chapter("act_1", content(1, text = "In the  beginning ")))
        val b = fingerprint(chapter("act_1", content(1, text = " In the\nbeginning")))

        assertEquals(a.textFingerprint, b.textFingerprint)
    }

    /** The chapter chunk (META) and titles carry text too, but aren't verses. */
    @Test
    fun `only verse content counts`() {
        val plain = fingerprint(chapter("act_1", content(1)))
        val withMeta = fingerprint(chapter("act_1", content(1, 1, "1. verse 1", ContentType.META), content(1)))

        assertEquals(plain, withMeta)
    }

    @Test
    fun `same edition ignores nothing but the label`() {
        val key = EditionIdentityKey("en", "ulb", "Wycliffe Associates", "s", "t")

        assertTrue(isSameEdition(key, key.copy()))
        assertFalse(isSameEdition(key, key.copy(structureFingerprint = "s2")))
        assertFalse(isSameEdition(key, key.copy(textFingerprint = "t2")))
        assertFalse(isSameEdition(key, key.copy(creator = "other")))
    }
}
