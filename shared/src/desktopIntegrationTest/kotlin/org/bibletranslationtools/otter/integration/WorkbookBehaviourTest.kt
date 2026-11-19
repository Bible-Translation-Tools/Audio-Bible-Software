package org.bibletranslationtools.otter.integration

import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.otter.common.data.primitives.CheckingStatus
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.Content
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.TakeCheckingState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the workbook must do. Each test drives the workbook as the screens do and checks both what a
 * reader sees once the change returns and what reached the database: a change is saved before it
 * returns, so nothing here waits for a background write.
 */
class WorkbookBehaviourTest {

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

    /** Two chunks covering Jude 1: verses 1-10 and 11-25. */
    private fun twoChunks() = listOf(
        Content(sort = 1, labelKey = "chunk", start = 1, end = 10, text = null, format = null, type = ContentType.TEXT, draftNumber = 0),
        Content(sort = 2, labelKey = "chunk", start = 11, end = 25, text = null, format = null, type = ContentType.TEXT, draftNumber = 0)
    )

    /** The verse ranges of [chapter]'s chunks, loading them if needed. */
    private suspend fun textRanges(chapter: Chapter) =
        chapter.chunks().filter { it.contentType == ContentType.TEXT }.map { it.start to it.end }

    @Test
    fun `recording a take saves it and selects it`() = runBlocking<Unit> {
        val workbook = env.workbook(project)
        val chunk = env.verseChunk(workbook, 1, 1)

        val take = chunk.audio.insertTake(env.newTake(project, 1, "new_v01_t1.wav")).saved()

        assertTrue(take in chunk.audio.getAllTakes(), "a recorded take is listed once it is saved")
        assertEquals(take, chunk.audio.getSelectedTake())
        val selected = assertNotNull(env.selectedTakeOn(project, 1, 1), "the selection was not saved")
        assertEquals(take.name, env.take(selected).filename)
    }

    @Test
    fun `selecting a take is visible at once and saved`() = runBlocking<Unit> {
        val first = env.addTake(project, 1, 1)
        val workbook = env.workbook(project)
        val take = env.workbookTake(workbook, 1, 1, first)
        val chunk = env.verseChunk(workbook, 1, 1)

        chunk.audio.selectTake(take).saved()

        assertEquals(take, chunk.audio.getSelectedTake(), "read back on the next line")
        assertEquals(first, env.selectedTakeOn(project, 1, 1), "the selection was not saved")
    }

    @Test
    fun `deleting the selected take deselects it at once and saves the delete`() = runBlocking<Unit> {
        val takeId = env.recordTake(project, 1, 1)
        val workbook = env.workbook(project)
        val take = env.workbookTake(workbook, 1, 1, takeId)
        val chunk = env.verseChunk(workbook, 1, 1)
        assertEquals(take, chunk.audio.getSelectedTake(), "the selection loads with the chunk")

        chunk.audio.deleteTake(take).saved()

        assertNull(chunk.audio.getSelectedTake(), "read back on the next line")
        assertTrue(chunk.audio.take(take.id)!!.isDeleted())
        assertNotNull(env.take(takeId).deletedTs, "the delete was not saved")
        assertNull(env.selectedTakeOn(project, 1, 1), "the deselection was not saved")
    }

    @Test
    fun `approving a take saves its checking status`() = runBlocking<Unit> {
        val takeId = env.recordTake(project, 1, 1)
        val workbook = env.workbook(project)
        val take = env.workbookTake(workbook, 1, 1, takeId)
        val chunk = env.verseChunk(workbook, 1, 1)
        val passed = env.db.checkingStatusDao.fetchId(CheckingStatus.PEER_EDIT)

        val approved = chunk.audio.setChecking(take, TakeCheckingState(CheckingStatus.PEER_EDIT, take.checksum())).saved()

        assertEquals(CheckingStatus.PEER_EDIT, approved.checking.status)
        assertEquals(CheckingStatus.PEER_EDIT, chunk.checkingStatus())
        assertEquals(passed, env.take(takeId).checkingFk, "the checking status was not saved")
        assertEquals(take.checksum(), env.take(takeId).checksum)
    }

    @Test
    fun `replacing a chapter's chunks gives readers the new layout`() = runBlocking<Unit> {
        val workbook = env.workbook(project)
        val chapter = env.chapter(workbook, 1)
        val before = chapter.chunkCount()
        assertTrue(before > 2, "the verse-by-verse project starts with one row per verse")

        chapter.resetChunks().saved()
        chapter.addChunks(twoChunks()).saved()

        assertEquals(listOf(1 to 10, 11 to 25), textRanges(chapter))
        assertEquals(2, env.chunkCount(project, 1))
    }
}
