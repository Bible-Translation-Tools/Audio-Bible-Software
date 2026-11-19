package org.bibletranslationtools.otter.integration

import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.otter.common.data.primitives.CheckingStatus
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.Content
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.data.workbook.RejectReason
import org.bibletranslationtools.otter.common.data.workbook.SaveResult
import org.bibletranslationtools.otter.common.data.workbook.TakeCheckingState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Defects the workbook redesign found by reading the code, each pinned as the behaviour it now has,
 * so none comes back.
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

    /** Rechunks chapter 1 of [project] into two chunks and makes it stick, as leaving the chapter does. */
    private suspend fun rechunkAndSettle() {
        val chapter = env.chapter(env.workbook(project), 1)
        chapter.rechunk(
            listOf(
                Content(sort = 1, labelKey = "chunk", start = 1, end = 10, text = null, format = null, type = ContentType.TEXT, draftNumber = 0),
                Content(sort = 2, labelKey = "chunk", start = 11, end = 25, text = null, format = null, type = ContentType.TEXT, draftNumber = 0)
            )
        ).saved()
        chapter.settle().saved()
    }

    @Test
    fun `deleting a take keeps the checking status saved since it loaded`() = runBlocking<Unit> {
        val takeId = env.recordTake(project, 1, 1)
        val workbook = env.workbook(project)
        val take = env.workbookTake(workbook, 1, 1, takeId)
        val audio = env.verseChunk(workbook, 1, 1).audio
        val peerEdit = env.db.checkingStatusDao.fetchId(CheckingStatus.PEER_EDIT)

        audio.setChecking(take, TakeCheckingState(CheckingStatus.PEER_EDIT, take.checksum())).saved()
        audio.deleteTake(take).saved()

        assertNotNull(env.take(takeId).deletedTs, "the delete was not saved")
        assertEquals(peerEdit, env.take(takeId).checkingFk, "the delete put the old checking status back")
        assertEquals(CheckingStatus.PEER_EDIT, audio.take(take.id)?.checking?.status)
    }

    @Test
    fun `approving a deleted take keeps it deleted`() = runBlocking<Unit> {
        val takeId = env.recordTake(project, 1, 1)
        val workbook = env.workbook(project)
        val take = env.workbookTake(workbook, 1, 1, takeId)
        val audio = env.verseChunk(workbook, 1, 1).audio
        val peerEdit = env.db.checkingStatusDao.fetchId(CheckingStatus.PEER_EDIT)

        audio.deleteTake(take).saved()
        val deleted = assertNotNull(audio.take(take.id))
        audio.setChecking(deleted, TakeCheckingState(CheckingStatus.PEER_EDIT, take.checksum())).saved()

        assertEquals(peerEdit, env.take(takeId).checkingFk, "the checking status was not saved")
        assertNotNull(env.take(takeId).deletedTs, "saving the checking status restored the take")
        assertTrue(audio.take(take.id)!!.isDeleted())
    }

    @Test
    fun `undoing a delete is saved, so the take survives the startup purge`() = runBlocking<Unit> {
        val takeId = env.addTake(project, 1, 1)
        val workbook = env.workbook(project)
        val take = env.workbookTake(workbook, 1, 1, takeId)
        val audio = env.verseChunk(workbook, 1, 1).audio

        audio.deleteTake(take).saved()
        assertNotNull(env.take(takeId).deletedTs, "the delete was not saved")
        audio.restoreTake(take).saved()
        assertNull(env.take(takeId).deletedTs, "the undo was not saved")
        env.purgeDeletedTakesAsStartupDoes()

        assertNotNull(env.takeOrNull(takeId), "the restored take's row was purged")
        assertTrue(env.takeFile(takeId).exists(), "the restored take's audio was purged")
    }

    @Test
    fun `rechunking one project leaves another project's deleted takes alone`() = runBlocking<Unit> {
        val source = env.sourceEditions().single()
        val other = env.createProject(env.sourceBook("jud", source.id), env.language("es"), deriveProjectFromVerses = true)
        val pending = env.addTake(other, 1, 1, deleted = true)
        val pendingFile = env.takeFile(pending)

        rechunkAndSettle()

        assertNotNull(env.takeOrNull(pending), "another project's undoable delete was purged")
        assertTrue(pendingFile.exists(), "another project's deleted take lost its audio")
    }

    @Test
    fun `rechunking removes the old chunks' take files`() = runBlocking<Unit> {
        val takeId = env.addTake(project, 1, 1)
        val file = env.takeFile(takeId)

        rechunkAndSettle()

        assertNull(env.takeOrNull(takeId))
        assertFalse(file.exists(), "the old take's audio was left on disk with no row")
    }

    @Test
    fun `a closed workbook saves nothing`() = runBlocking<Unit> {
        val takeId = env.addTake(project, 1, 1)
        val workbook = env.workbook(project)
        val take = env.workbookTake(workbook, 1, 1, takeId)
        val chunk = env.verseChunk(workbook, 1, 1)

        env.closeWorkbook(workbook)
        val result = chunk.audio.selectTake(take)

        assertEquals(SaveResult.Rejected(RejectReason.CLOSED), result)
        assertNull(chunk.audio.getSelectedTake(), "a refused change still showed in memory")
        assertEquals(null, env.selectedTakeOn(project, 1, 1), "a change to a closed workbook was saved")
    }
}
