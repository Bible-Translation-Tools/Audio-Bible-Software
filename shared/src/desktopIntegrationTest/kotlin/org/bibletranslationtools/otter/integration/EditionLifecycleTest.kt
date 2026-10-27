package org.bibletranslationtools.otter.integration

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Editions are never removed automatically (O1-Q5): not when a newer one arrives, not when a
 * project leaves one. Removing one by hand needs nothing to use it.
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
    fun `a newer edition is installed beside an older one nothing uses`() {
        val environment = environment().import("en_ulb.zip")
        val older = environment.sourceEditions().single()

        environment.import(environment.newerEdition("en_ulb.zip"))

        assertEquals(listOf("2017-11-29", "2024-07-12"), environment.sourceEditions().map { it.issued })
        assertTrue(File(older.path).exists(), "never removed automatically (O1-Q5)")
    }

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
    fun `deleting the last project of an older edition keeps that edition`() {
        val environment = environment().import("en_ulb.zip")
        val older = environment.sourceEditions().single()
        environment.projectFrom(older.id)
        environment.import(environment.newerEdition("en_ulb.zip"))

        environment.deleteAllProjects()

        assertEquals(2, environment.sourceEditions().size)
        assertTrue(File(older.path).exists())
    }

    @Test
    fun `an edition a project uses can't be removed`() {
        val environment = environment().import("en_ulb.zip")
        val edition = environment.sourceEditions().single()
        environment.projectFrom(edition.id)

        assertFalse(environment.removeEdition(edition.id))
        assertTrue(File(edition.path).exists())
    }

    @Test
    fun `an edition nothing uses can be removed, files and all`() {
        val environment = environment().import("en_ulb.zip")
        val edition = environment.sourceEditions().single()

        assertTrue(environment.removeEdition(edition.id))

        assertTrue(environment.sourceEditions().isEmpty())
        assertFalse(File(edition.path).exists())
    }
}
