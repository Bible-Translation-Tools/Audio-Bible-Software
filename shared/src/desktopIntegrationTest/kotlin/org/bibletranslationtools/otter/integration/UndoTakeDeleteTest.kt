package org.bibletranslationtools.otter.integration

import org.bibletranslationtools.otter.common.api.persistence.repositories.ITakeRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.domain.translation.TranslationTakeDeleteAction
import java.io.File
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Relay defect 1 of the RxJava migration audit: undoing a take delete is not saved.
 *
 * This pins the DEFECT, so it passes on today's code. Phase 8 fixes it and flips the assertions
 * marked "defect".
 */
class UndoTakeDeleteTest {

    private var env: IntegrationEnvironment? = null

    @AfterTest
    fun tearDown() {
        env?.close()
        env = null
    }

    /** The workbook's writes are fire-and-forget on io threads, so a row has to be waited for. */
    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for: $what")
            Thread.sleep(20)
        }
    }

    @Test
    fun `a take restored by undo is still purged at the next startup`() {
        val environment = IntegrationEnvironment.create().also { env = it }
        environment.import("en_ulb.zip")
        val project = environment.createProject(
            environment.sourceBook("jud", environment.sourceEditions().single().id), environment.language("fr"),
            mode = ProjectMode.NARRATION, deriveProjectFromVerses = true
        )
        val takeId = environment.recordTake(project, sort = 1, verse = 1)
        val file = File(URI("file:${environment.take(takeId).filepath}"))

        val workbook = assertNotNull(environment.koinGet<IWorkbookRepository>().getWorkbook(project).blockingGet())
        val chunk = workbook.target.chapters.toList().blockingGet().single { it.sort == 1 }
            .chunks.blockingGet().single { it.contentType == ContentType.TEXT && it.start == 1 }
        waitFor("the take to load") { chunk.audio.getAllTakes().isNotEmpty() }
        val take = chunk.audio.getAllTakes().single()
        assertEquals(take, chunk.audio.getSelectedTake())

        // Delete, as Orature's blind draft does.
        val delete = TranslationTakeDeleteAction(chunk, take, isTakeSelected = true) { _, _ -> }
        delete.execute()
        waitFor("the delete to be saved") { environment.take(takeId).deletedTs != null }
        waitFor("the deselect to be saved") { environment.selectedTakeOn(project, 1, 1) == null }

        // Undo. In memory the take is back and selected again.
        delete.undo()
        assertFalse(take.isDeleted())
        assertEquals(take, chunk.audio.getSelectedTake())
        // The undo's other write, the re-select, does reach the database...
        waitFor("the re-select to be saved") { environment.selectedTakeOn(project, 1, 1) == takeId }
        Thread.sleep(500) // ...and nothing else is on its way: no write is started for "not deleted".
        assertNotNull(environment.take(takeId).deletedTs, "defect: the row is still marked deleted")

        // Next launch: what InitializeTakeRepository runs.
        val takes = environment.koinGet<ITakeRepository>()
        takes.removeNonExistentTakes().andThen(takes.deleteExpiredTakes()).blockingAwait()

        assertFalse(file.exists(), "defect: the restored recording's file is deleted")
        assertTrue(environment.takesOn(project, 1, 1).isEmpty(), "defect: the restored take's row is deleted")
        assertEquals(null, environment.selectedTakeOn(project, 1, 1), "defect: the verse is left with no selected take")
    }
}
