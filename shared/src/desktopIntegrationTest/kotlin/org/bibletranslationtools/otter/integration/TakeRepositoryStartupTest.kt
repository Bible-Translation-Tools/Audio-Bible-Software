package org.bibletranslationtools.otter.integration

import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.otter.common.api.persistence.repositories.ITakeRepository
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import java.io.File
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The take repository's suspend functions that startup calls, against a real database. Two of them
 * run on every launch and delete rows and files, so what they leave alone matters as much as what
 * they remove.
 */
class TakeRepositoryStartupTest {
    private var env: IntegrationEnvironment? = null

    @AfterTest
    fun tearDown() {
        env?.close()
        env = null
    }

    private fun fileOf(path: String) = File(URI("file:$path"))

    private val IntegrationEnvironment.takes: ITakeRepository get() = koinForTests.get()

    /** A French Acts project with a selected take on 19:40 and one on 19:41. */
    private fun setup(): Triple<IntegrationEnvironment, Collection, Pair<Int, Int>> {
        val environment = IntegrationEnvironment.create().also { env = it }
        environment.import("en_ulb.zip")
        val project = environment.createProject(
            environment.sourceBook("act", environment.sourceEditions().single().id), environment.language("fr"),
            mode = ProjectMode.NARRATION, deriveProjectFromVerses = true
        )
        val first = environment.recordTake(project, sort = 19, verse = 40)
        val second = environment.recordTake(project, sort = 19, verse = 41)
        return Triple(environment, project, first to second)
    }

    @Test
    fun `a take whose file is gone is removed and unselected, the others are left alone`() {
        val (environment, project, ids) = setup()
        val (kept, gone) = ids
        fileOf(environment.take(gone).filepath).delete()

        runBlocking { environment.takes.removeNonExistentTakesSuspend() }

        assertEquals(listOf(40), environment.versesWithTakes(project, 19))
        assertNull(environment.selectedTakeOn(project, 19, 41), "the verse no longer selects the removed take")
        assertEquals(kept, environment.selectedTakeOn(project, 19, 40))
        assertTrue(fileOf(environment.take(kept).filepath).exists())
    }

    @Test
    fun `a soft-deleted take past its expiry is removed with its file, a live one is not`() {
        val (environment, project, ids) = setup()
        val (live, deleted) = ids
        val deletedRow = environment.take(deleted)
        val deletedFile = fileOf(deletedRow.filepath)
        // As the app leaves a take the user deleted: unselected, and marked with the day it was deleted.
        val content = environment.db.contentDao.fetchById(deletedRow.contentFk)
        environment.db.contentDao.update(content.copy(selectedTakeFk = null))
        environment.db.takeDao.update(deletedRow.copy(deletedTs = "2026-01-01"))

        runBlocking { environment.takes.deleteExpiredTakesSuspend() }

        assertTrue(environment.takesOn(project, 19, 41).isEmpty(), "the expired take's row is gone")
        assertFalse(deletedFile.exists(), "and so is its file")
        assertEquals(listOf(live), environment.takesOn(project, 19, 40).map { it.id })
        assertTrue(fileOf(environment.take(live).filepath).exists())
    }

    @Test
    fun `updating a take writes its new number and name`() {
        val (environment, _, ids) = setup()
        val id = ids.first
        val take = runBlocking { environment.takes.getAllSuspend() }.single { it.id == id }

        runBlocking { environment.takes.updateSuspend(take.copy(number = 7, filename = "renamed_t7.wav")) }

        val row = environment.take(id)
        assertEquals(7, row.number)
        assertEquals("renamed_t7.wav", row.filename)
        assertEquals(1, environment.take(ids.second).number, "the other take is untouched")
    }
}
