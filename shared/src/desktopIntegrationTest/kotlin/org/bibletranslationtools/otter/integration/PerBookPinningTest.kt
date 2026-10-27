package org.bibletranslationtools.otter.integration

import org.wycliffeassociates.resourcecontainer.ResourceContainer
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Books of one target language translated from different editions of their source: the two
 * English ULB zips that both say `version: '12'`.
 */
class PerBookPinningTest {

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

    private fun IntegrationEnvironment.project(book: String, sourceId: Int) =
        createProject(sourceBook(book, sourceId), language("fr"))

    @Test
    fun `each book points at the edition it was created from`() {
        val environment = environment().importBoth()
        val (fixture, bundled) = environment.sourceEditions()

        val jude = environment.project("jud", fixture.id)
        val john3 = environment.project("3jn", bundled.id)

        assertEquals(fixture.id, jude.resourceContainer?.let { environment.derivedFromOf(it.id) })
        assertEquals(bundled.id, john3.resourceContainer?.let { environment.derivedFromOf(it.id) })
    }

    /** Before, both went to `der/…/en_ulb/v12/fr`, and the second overwrote the first's manifest. */
    @Test
    fun `editions with the same label get separate derived containers`() {
        val environment = environment().importBoth()
        val (fixture, bundled) = environment.sourceEditions()

        val jude = environment.project("jud", fixture.id).resourceContainer!!
        val john3 = environment.project("3jn", bundled.id).resourceContainer!!

        assertNotEquals(jude.path, john3.path)
        assertEquals(listOf("jud"), ResourceContainer.load(jude.path).use { rc -> rc.manifest.projects.map { it.identifier } })
        assertEquals(listOf("3jn"), ResourceContainer.load(john3.path).use { rc -> rc.manifest.projects.map { it.identifier } })
    }

    @Test
    fun `a new project's folder has no version in it`() {
        val environment = environment().import("en_ulb.zip")
        val source = environment.sourceEditions().single()
        val project = environment.project("jud", source.id)

        val directory = environment.projectDirectory(project)

        assertTrue(directory.isDirectory)
        assertTrue(
            directory.invariantSeparatorsPath.endsWith("/en_ulb/fr_ulb/jud"),
            "project folder ${directory.invariantSeparatorsPath}"
        )
    }
}
