package org.bibletranslationtools.otter.integration

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What happens to older editions of a source when a newer one arrives or a project leaves one.
 * The "newer" edition is the committed English ULB fixture with later issued and modified dates
 * and one verse reworded.
 */
class EditionLifecycleTest {

    private var env: IntegrationEnvironment? = null

    @AfterTest
    fun tearDown() {
        env?.close()
        env = null
    }

    private fun environment() = IntegrationEnvironment.create().also { env = it }

    private fun IntegrationEnvironment.projectFrom(sourceId: Int) =
        createProject(sourceBook("jud", sourceId), language("en"))

    @Test
    fun `a newer edition replaces an older one nothing uses`() {
        val environment = environment().import("en_ulb.zip")
        val older = environment.sourceEditions().single()

        environment.import(environment.newerEdition("en_ulb.zip"))

        val editions = environment.sourceEditions()
        assertEquals(1, editions.size)
        assertEquals("2024-07-12", editions.single().issued)
        assertFalse(File(older.path).exists(), "the replaced edition's files are gone")
    }

    @Test
    fun `an older edition a project uses is kept beside the newer one`() {
        val environment = environment().import("en_ulb.zip")
        val older = environment.sourceEditions().single()
        environment.projectFrom(older.id)

        environment.import(environment.newerEdition("en_ulb.zip"))

        assertEquals(2, environment.sourceEditions().size)
        assertTrue(File(older.path).exists())
    }

    /** Importing an older edition on purpose (for example to downgrade to) keeps it. */
    @Test
    fun `an older edition imported after a newer one is kept`() {
        val environment = environment()
        environment.import(environment.newerEdition("en_ulb.zip"))

        environment.import("en_ulb.zip")

        assertEquals(listOf("2024-07-12", "2017-11-29"), environment.sourceEditions().map { it.issued })
    }

    /** The two English "v12" zips have the same dates: neither replaces the other. */
    @Test
    fun `sibling editions are both kept`() {
        val environment = environment().import("en_ulb.zip")

        environment.import(environment.bundledSource("en_ulb.zip"))

        assertEquals(2, environment.sourceEditions().size)
    }

    @Test
    fun `deleting the last project of a superseded edition removes that edition`() {
        val environment = environment().import("en_ulb.zip")
        val older = environment.sourceEditions().single()
        environment.projectFrom(older.id)
        environment.import(environment.newerEdition("en_ulb.zip"))

        environment.deleteAllProjects()

        assertEquals(listOf("2024-07-12"), environment.sourceEditions().map { it.issued })
        assertFalse(File(older.path).exists())
    }

    @Test
    fun `deleting the last project of the newest edition keeps it`() {
        val environment = environment().import("en_ulb.zip")
        environment.projectFrom(environment.sourceEditions().single().id)

        environment.deleteAllProjects()

        assertEquals(1, environment.sourceEditions().size)
    }
}
