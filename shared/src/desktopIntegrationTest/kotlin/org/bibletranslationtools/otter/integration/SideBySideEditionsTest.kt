package org.bibletranslationtools.otter.integration

import org.bibletranslationtools.otter.common.domain.resourcecontainer.DeleteResult
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Several editions of one source installed at once, using the two English ULB zips that both say
 * `version: '12'` but differ in Acts 19 and 2 Cor 13: the committed fixture (older) and the one
 * the app bundles (newer).
 */
class SideBySideEditionsTest {

    private var env: IntegrationEnvironment? = null

    @AfterTest
    fun tearDown() {
        env?.close()
        env = null
    }

    private fun environment() = IntegrationEnvironment.create().also { env = it }

    private fun IntegrationEnvironment.importBoth() = apply {
        import("en_ulb.zip")
        import(bundledSource("en_ulb.zip"))
    }

    @Test
    fun `a second edition is installed beside the first`() {
        val editions = environment().importBoth().sourceEditions()

        assertEquals(2, editions.size)
        assertEquals(listOf("12", "12"), editions.map { it.version })
        assertNotEquals(editions[0].path, editions[1].path)
    }

    @Test
    fun `each edition gets its own folder named by label and content`() {
        val environment = environment().importBoth()

        val folders = environment.sourceEditions().map { edition ->
            File(edition.path).relativeTo(environment.sourceRoot).invariantSeparatorsPath.split('/').take(3)
        }

        folders.forEach { (_, source, edition) ->
            assertEquals("en_ulb", source)
            assertTrue(Regex("v12-[0-9a-f]{6}").matches(edition), "edition folder '$edition'")
        }
        assertNotEquals(folders[0], folders[1])
    }

    @Test
    fun `nothing is left in the staging folder`() {
        val environment = environment().importBoth()

        val staging = environment.sourceRoot.resolve(".staging")
        assertTrue(!staging.exists() || staging.list().isNullOrEmpty(), "staging holds ${staging.list()?.toList()}")
    }

    /** Re-importing an installed edition merges its media and adds no row. */
    @Test
    fun `importing an installed edition again adds nothing`() {
        val environment = environment().importBoth()

        environment.import(environment.bundledSource("en_ulb.zip"))

        assertEquals(2, environment.sourceEditions().size)
    }

    @Test
    fun `each edition keeps its own verse structure`() {
        val environment = environment().importBoth()
        val (fixture, bundled) = environment.sourceEditions()

        assertEquals(41, environment.verseCounts("act_19", fixture.id).total)
        assertEquals(40, environment.verseCounts("act_19", bundled.id).total)
    }

    @Test
    fun `already imported means this edition, not any edition of the source`() {
        val environment = environment()
        environment.import("en_ulb.zip")

        assertTrue(environment.isAlreadyImported(environment.fixture("en_ulb.zip")))
        assertFalse(environment.isAlreadyImported(environment.bundledSource("en_ulb.zip")))
    }

    @Test
    fun `deleting one edition leaves the other`() {
        val environment = environment().importBoth()
        val (fixture, bundled) = environment.sourceEditions()

        assertEquals(DeleteResult.SUCCESS, environment.deleteSource(fixture.path))

        assertEquals(listOf(bundled.id), environment.sourceEditions().map { it.id })
        assertFalse(File(fixture.path).exists(), "the deleted edition's files are gone")
        assertTrue(File(bundled.path).exists(), "the other edition's files remain")
    }
}
