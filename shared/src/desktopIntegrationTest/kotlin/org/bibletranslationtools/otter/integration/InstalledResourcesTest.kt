package org.bibletranslationtools.otter.integration

import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.otter.common.domain.resourcecontainer.InstalledResources
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Resources drawer's data: what each installed source takes, what uses it, removing one. */
class InstalledResourcesTest {

    private var env: IntegrationEnvironment? = null

    @AfterTest
    fun tearDown() {
        env?.close()
        env = null
    }

    @Test
    fun `each source shows its size and what uses it, and only an unused one can be removed`() {
        val environment = IntegrationEnvironment.create().also { env = it }
        environment.import("en_ulb.zip")
        environment.import(environment.bundledSource("en_ulb.zip"))
        val (used, unused) = environment.sourceEditions()
        environment.createProject(environment.sourceBook("jud", used.id), environment.language("fr"))
        val resources = environment.koinGet<InstalledResources>()

        val listed = runBlocking { resources.list() }.associateBy { it.edition.id }

        assertEquals(setOf(used.id, unused.id), listed.keys)
        assertTrue(listed.values.all { it.sizeBytes > 0 }, "sizes are measured")
        assertEquals(listOf("fr"), listed.getValue(used.id).usedByLanguages.map { it.slug })
        assertFalse(listed.getValue(used.id).removable)
        assertTrue(listed.getValue(unused.id).removable)

        assertFalse(runBlocking { resources.remove(listed.getValue(used.id).edition) }, "in use")
        assertTrue(runBlocking { resources.remove(listed.getValue(unused.id).edition) })
        assertEquals(listOf(used.id), environment.sourceEditions().map { it.id })
        assertFalse(File(unused.path).exists(), "its files are gone")
    }
}
