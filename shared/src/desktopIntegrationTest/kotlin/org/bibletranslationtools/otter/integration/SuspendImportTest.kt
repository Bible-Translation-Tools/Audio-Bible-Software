package org.bibletranslationtools.otter.integration

import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import java.io.File
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The importers' suspend path, which startup uses. It is a copy of the Rx path the other import
 * tests drive, so the same cases are run through it: a source, and a backup merged into a project
 * that is already here.
 */
class SuspendImportTest {
    private var env: IntegrationEnvironment? = null

    @AfterTest
    fun tearDown() {
        env?.close()
        env = null
    }

    private fun fileOf(path: String) = File(URI("file:$path"))

    @Test
    fun `a source and then a backup with a different recording import as they do through Rx`() {
        val environment = IntegrationEnvironment.create().also { env = it }

        val sourceResult = runBlocking { environment.importer.importSuspend(environment.fixture("en_ulb.zip")) }
        assertEquals(ImportResult.SUCCESS, sourceResult, "importing the source")

        val project = environment.createProject(
            environment.sourceBook("act", environment.sourceEditions().single().id), environment.language("fr"),
            mode = ProjectMode.NARRATION, deriveProjectFromVerses = true
        )
        val take = environment.take(environment.recordTake(project, sort = 19, verse = 41))
        fileOf(take.filepath).writeText("local recording")
        val backup = environment.backup(project)
        val local = environment.takesOn(project, 19, 41).single()
        val entry = environment.zipEntries(backup).single { it.endsWith("_v41_t1.wav") }
        val imported = environment.withEntryReplaced(backup, entry, "imported recording".toByteArray())

        val backupResult = runBlocking { environment.importer.importSuspend(imported) }
        assertEquals(ImportResult.SUCCESS, backupResult, "importing the backup")

        val takes = environment.takesOn(project, 19, 41)
        assertEquals(listOf(1, 2), takes.map { it.number })
        assertEquals("local recording", fileOf(takes[0].filepath).readText(), "the local take is untouched")
        assertEquals("imported recording", fileOf(takes[1].filepath).readText())
        assertNotEquals(takes[0].filepath, takes[1].filepath)
        assertEquals(local.id, takes[0].id)
        assertEquals(takes[1].id, environment.selectedTakeOn(project, 19, 41), "the backup's selection is applied")
    }
}
