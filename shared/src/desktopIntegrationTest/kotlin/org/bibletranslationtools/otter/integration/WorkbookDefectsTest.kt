package org.bibletranslationtools.otter.integration

import org.bibletranslationtools.otter.common.data.primitives.CheckingStatus
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.DateHolder
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.otter.common.data.workbook.TakeCheckingState
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Defects the workbook redesign found by reading the code, each pinned as the behaviour it should
 * have. A test stays ignored, with the step that fixes it named, until that fix lands.
 */
class WorkbookDefectsTest {

    private lateinit var env: IntegrationEnvironment
    private lateinit var project: Collection

    @BeforeTest
    fun setUp() {
        env = IntegrationEnvironment.create()
        env.import("en_ulb.zip")
        val source = env.sourceEditions().single()
        project = env.createProject(env.sourceBook("jud", source.id), env.language("fr"), deriveProjectFromVerses = true)
    }

    @AfterTest
    fun tearDown() {
        env.close()
    }

    private fun chapter(workbook: Workbook, sort: Int): Chapter =
        workbook.target.chapters.filter { it.sort == sort }.blockingFirst()

    /** The workbook's own [Take] for row [takeId], once its takes have loaded. */
    private fun workbookTake(workbook: Workbook, sort: Int, verse: Int, takeId: Int): Take {
        val name = env.take(takeId).filename
        val chunk = chapter(workbook, sort).chunks.blockingGet().single { it.start == verse && it.label == "verse" }
        var found: Take? = null
        env.eventually("take $takeId loads into the workbook") {
            found = chunk.audio.getAllTakes().firstOrNull { it.name == name }
            found != null
        }
        return found!!
    }

    @Test
    fun `deleting a take keeps the checking status saved since it loaded`() {
        val takeId = env.recordTake(project, 1, 1)
        val take = workbookTake(env.workbook(project), 1, 1, takeId)
        val peerEdit = env.db.checkingStatusDao.fetchId(CheckingStatus.PEER_EDIT)

        take.checkingState.accept(TakeCheckingState(CheckingStatus.PEER_EDIT, take.checksum()))
        env.eventually("the checking status is saved") { env.take(takeId).checkingFk == peerEdit }
        take.deletedTimestamp.accept(DateHolder.now())
        env.eventually("the delete is saved") { env.take(takeId).deletedTs != null }

        assertEquals(peerEdit, env.take(takeId).checkingFk, "the delete put the old checking status back")
    }

    @Test
    fun `approving a deleted take keeps it deleted`() {
        val takeId = env.recordTake(project, 1, 1)
        val take = workbookTake(env.workbook(project), 1, 1, takeId)
        val peerEdit = env.db.checkingStatusDao.fetchId(CheckingStatus.PEER_EDIT)

        take.deletedTimestamp.accept(DateHolder.now())
        env.eventually("the delete is saved") { env.take(takeId).deletedTs != null }
        take.checkingState.accept(TakeCheckingState(CheckingStatus.PEER_EDIT, take.checksum()))
        env.eventually("the checking status is saved") { env.take(takeId).checkingFk == peerEdit }

        assertNotNull(env.take(takeId).deletedTs, "saving the checking status restored the take")
    }

    @Test
    fun `undoing a delete is saved, so the take survives the startup purge`() {
        val takeId = env.addTake(project, 1, 1)
        val take = workbookTake(env.workbook(project), 1, 1, takeId)

        take.deletedTimestamp.accept(DateHolder.now())
        env.eventually("the delete is saved") { env.take(takeId).deletedTs != null }
        take.deletedTimestamp.accept(DateHolder.empty)
        env.eventually("the undo is saved") { env.take(takeId).deletedTs == null }
        env.purgeDeletedTakesAsStartupDoes()

        assertNotNull(env.takeOrNull(takeId), "the restored take's row was purged")
        assertTrue(env.takeFile(takeId).exists(), "the restored take's audio was purged")
    }

    @Ignore("Defect: rechunking purges soft-deleted takes in every project. Fixed in step 1.2.")
    @Test
    fun `rechunking one project leaves another project's deleted takes alone`() {
        val source = env.sourceEditions().single()
        val other = env.createProject(env.sourceBook("jud", source.id), env.language("es"), deriveProjectFromVerses = true)
        val pending = env.addTake(other, 1, 1, deleted = true)
        val pendingFile = env.takeFile(pending)

        chapter(env.workbook(project), 1).reset().blockingAwait()

        assertNotNull(env.takeOrNull(pending), "another project's undoable delete was purged")
        assertTrue(pendingFile.exists(), "another project's deleted take lost its audio")
    }

    @Ignore("Defect: rechunking orphans the old chunks' take files. Fixed in step 1.2.")
    @Test
    fun `rechunking removes the old chunks' take files`() {
        val takeId = env.addTake(project, 1, 1)
        val file = env.takeFile(takeId)

        chapter(env.workbook(project), 1).reset().blockingAwait()

        assertNull(env.takeOrNull(takeId))
        assertFalse(file.exists(), "the old take's audio was left on disk with no row")
    }

    @Ignore("Defect: a closed workbook keeps saving. Fixed in step 7.1.")
    @Test
    fun `a closed workbook saves nothing`() {
        val takeId = env.addTake(project, 1, 1)
        val workbook = env.workbook(project)
        val take = workbookTake(workbook, 1, 1, takeId)
        val chunk = chapter(workbook, 1).chunks.blockingGet().single { it.start == 1 && it.label == "verse" }

        env.closeWorkbook(workbook)
        chunk.audio.selectTake(take)
        Thread.sleep(500)

        assertEquals(null, env.selectedTakeOn(project, 1, 1), "a change to a closed workbook was saved")
    }
}
