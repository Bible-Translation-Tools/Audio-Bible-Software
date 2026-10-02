package org.bibletranslationtools.otter.common.domain.resourcecontainer

import org.bibletranslationtools.otter.common.api.persistence.editionFolderName
import org.bibletranslationtools.otter.common.data.primitives.ContainerType
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditionOrderTest {

    private val english = Language("en", "English", "English", "ltr", true, "")

    private fun edition(id: Int, version: String, issued: String, modified: String = issued) = ResourceMetadata(
        conformsTo = "rc0.2", creator = "WA", description = "", format = "text/usfm", identifier = "ulb",
        issued = LocalDate.parse(issued), language = english, modified = LocalDate.parse(modified),
        publisher = "", subject = "Bible", type = ContainerType.Bundle, title = "ULB", version = version,
        license = "", path = File("/rc/$id"), id = id
    )

    @Test
    fun `a later issued date is newer, whatever the labels say`() {
        val v12 = edition(1, "12", "2017-11-29")
        val v2407 = edition(2, "24-07", "2024-07-12")

        assertEquals(v2407, EditionOrder.newest(listOf(v12, v2407)))
        assertTrue(EditionOrder.isNewer(v2407, v12))
    }

    @Test
    fun `the modified date breaks a tie on issued`() {
        val earlier = edition(1, "12", "2017-11-29", modified = "2018-01-01")
        val later = edition(2, "12", "2017-11-29", modified = "2021-03-16")

        assertEquals(later, EditionOrder.newest(listOf(earlier, later)))
    }

    /** The two English "v12" zips: same dates, neither replaces the other. */
    @Test
    fun `editions with the same dates are siblings, and the first installed comes first`() {
        val first = edition(1, "12", "2017-11-29")
        val second = edition(2, "12", "2017-11-29")

        assertFalse(EditionOrder.isNewer(first, second))
        assertFalse(EditionOrder.isNewer(second, first))
        assertEquals(first, EditionOrder.newest(listOf(second, first)))
    }

    @Test
    fun `no editions means no newest`() {
        assertNull(EditionOrder.newest(emptyList()))
    }

    @Test
    fun `items without an edition sort last`() {
        val sorted = listOf<ResourceMetadata?>(null, edition(1, "12", "2017-11-29"))
            .sortedWith(EditionOrder.newestFirstBy { it })

        assertEquals(listOf(1, null), sorted.map { it?.id })
    }

    @Test
    fun `an edition folder is the label and the short code`() {
        assertEquals("v12-3f9a2c", editionFolderName("12", "3f9a2c"))
        assertEquals("v24-07-3f9a2c", editionFolderName("24-07", "3f9a2c"))
        assertEquals("v12.1-3f9a2c", editionFolderName("\"12.1\"", "3f9a2c"), "quotes in the manifest are dropped")
        assertEquals("v1_2-3f9a2c", editionFolderName("1/2", "3f9a2c"), "unsafe characters are replaced")
        assertEquals("vnone-3f9a2c", editionFolderName("", "3f9a2c"))
    }

    @Test
    fun `the short code is six characters and follows the content`() {
        val a = EditionFingerprint("eng", "structure-a", "text-a", emptyList())

        assertEquals(6, a.shortCode.length)
        assertEquals(a.shortCode, a.copy(detectedVersification = "rsc").shortCode, "the versification isn't content")
        assertNotEquals(a.shortCode, a.copy(textFingerprint = "text-b").shortCode)
    }
}
