package org.bibletranslationtools.otter.integration

import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.domain.resourcecontainer.RcConstants
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import java.io.File
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    // The two restores below are BackupEditionRoundTripTest's, run through importSuspend: the project
    // is not on the device, so it has to be attached to an installed edition of its source.

    private val IntegrationEnvironment.older get() = sourceEditions().single { it.issued == "2017-11-29" && it.version == olderVersion }
    private val IntegrationEnvironment.newer get() = sourceEditions().single { it.issued == "2024-07-12" }
    private var olderVersion = ""

    /** The fixture installed, a French project of Acts on it with a take on 19:41, then a newer edition. */
    private fun projectOnOlderEdition(): Pair<IntegrationEnvironment, Collection> {
        val environment = IntegrationEnvironment.create().also { env = it }
        environment.import("en_ulb.zip")
        val older = environment.sourceEditions().single()
        olderVersion = older.version
        val project = environment.createProject(
            environment.sourceBook("act", older.id), environment.language("fr"),
            mode = ProjectMode.NARRATION, deriveProjectFromVerses = true
        )
        environment.recordTake(project, sort = 19, verse = 41)
        environment.import(environment.newerDatesOf(environment.bundledSource("en_ulb.zip"), null))
        return environment to project
    }

    private fun IntegrationEnvironment.restoreThroughSuspend(backup: File) {
        assertEquals(ImportResult.SUCCESS, runBlocking { importer.importSuspend(backup) }, "restoring the backup")
    }

    private fun IntegrationEnvironment.restoredProject(): Collection = derivedProjects().single { it.slug == "act" }

    @Test
    fun `a held-back chapter is restored on its edition, even where that edition wasn't installed`() {
        val (environment, project) = projectOnOlderEdition()
        runBlocking {
            val upgrade = environment.upgradeBookEdition
            upgrade.apply(upgrade.plan(project.id, environment.editionMetadata(environment.newer.id)))
        }
        val backup = environment.backup(project)
        assertTrue(RcConstants.SOURCE_EDITIONS_FILE in environment.zipEntries(backup))

        // As on another device: the project, and the older edition, gone.
        environment.deleteAllProjects()
        assertTrue(environment.removeEdition(environment.older.id), "the older edition is removed")

        environment.restoreThroughSuspend(backup)

        val restored = environment.restoredProject()
        assertEquals(environment.newer.id, environment.descriptorSourceEdition(restored), "the book is on the newer edition")
        assertEquals(environment.older.id, environment.structureEdition(restored, 19), "chapter 19 is on the older one")
        assertEquals(listOf(41), environment.versesWithTakes(restored, 19), "the take is back on 41")
        assertNull(environment.structureEdition(restored, 18))
    }

    @Test
    fun `a backup without an edition record attaches to the edition embedded in it`() {
        val (environment, project) = projectOnOlderEdition()
        val backup = environment.withoutEntries(environment.backup(project), RcConstants.OWN_APP_DIR)

        environment.deleteAllProjects()
        environment.restoreThroughSuspend(backup)

        val restored = environment.restoredProject()
        assertEquals(environment.older.id, environment.descriptorSourceEdition(restored), "attached to the embedded edition")
        assertEquals(listOf(41), environment.versesWithTakes(restored, 19))
    }

}
