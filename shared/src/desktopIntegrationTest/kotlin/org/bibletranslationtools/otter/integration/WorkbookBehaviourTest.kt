package org.bibletranslationtools.otter.integration

import org.bibletranslationtools.otter.common.data.primitives.CheckingStatus
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.Content
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.data.primitives.MimeType
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.DateHolder
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.otter.common.data.workbook.TakeCheckingState
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the workbook does today that the redesign must keep. Each test drives the workbook as the
 * screens do and checks both what a reader sees at once and what reaches the database.
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

    private fun chapter(workbook: Workbook, sort: Int): Chapter =
        workbook.target.chapters.filter { it.sort == sort }.blockingFirst()

    private fun workbookTake(workbook: Workbook, verse: Int, takeId: Int): Take {
        val name = env.take(takeId).filename
        val chunk = chapter(workbook, 1).chunks.blockingGet().single { it.start == verse && it.label == "verse" }
        var found: Take? = null
        env.eventually("take $takeId loads into the workbook") {
            found = chunk.audio.getAllTakes().firstOrNull { it.name == name }
            found != null
        }
        return found!!
    }

    private fun newTake(number: Int): Take {
        val file = env.projectDirectory(project).resolve(".apps/orature/takes/c01/new_v01_t$number.wav")
        file.parentFile.mkdirs()
        file.writeText("recording $number")
        return Take(file.name, file, number, MimeType.WAV, LocalDate.now())
    }

    @Test
    fun `recording a take saves it and selects it`() {
        val workbook = env.workbook(project)
        val chunk = chapter(workbook, 1).chunks.blockingGet().single { it.start == 1 && it.label == "verse" }
        val take = newTake(1)

        chunk.audio.insertTake(take)

        assertTrue(take in chunk.audio.getAllTakes(), "a recorded take is listed at once")
        env.eventually("the take is saved and selected") {
            val selected = env.selectedTakeOn(project, 1, 1)
            selected != null && env.take(selected).filename == take.name
        }
        assertEquals(take, chunk.audio.getSelectedTake())
    }

    @Test
    fun `selecting a take is visible at once and saved`() {
        val first = env.addTake(project, 1, 1)
        val workbook = env.workbook(project)
        val take = workbookTake(workbook, 1, first)
        val chunk = chapter(workbook, 1).chunks.blockingGet().single { it.start == 1 && it.label == "verse" }

        chunk.audio.selectTake(take)

        assertEquals(take, chunk.audio.getSelectedTake(), "read back on the next line")
        env.eventually("the selection is saved") { env.selectedTakeOn(project, 1, 1) == first }
    }

    @Test
    fun `deleting the selected take deselects it at once and saves the delete`() {
        val takeId = env.recordTake(project, 1, 1)
        val workbook = env.workbook(project)
        val take = workbookTake(workbook, 1, takeId)
        val chunk = chapter(workbook, 1).chunks.blockingGet().single { it.start == 1 && it.label == "verse" }
        env.eventually("the selection loads") { chunk.audio.getSelectedTake() == take }

        take.deletedTimestamp.accept(DateHolder.now())

        assertNull(chunk.audio.getSelectedTake(), "read back on the next line")
        assertTrue(take.isDeleted())
        env.eventually("the delete is saved") { env.take(takeId).deletedTs != null }
        env.eventually("the deselection is saved") { env.selectedTakeOn(project, 1, 1) == null }
    }

    @Test
    fun `approving a take saves its checking status`() {
        val takeId = env.recordTake(project, 1, 1)
        val workbook = env.workbook(project)
        val take = workbookTake(workbook, 1, takeId)
        val passed = env.db.checkingStatusDao.fetchId(CheckingStatus.PEER_EDIT)

        take.checkingState.accept(TakeCheckingState(CheckingStatus.PEER_EDIT, take.checksum()))

        env.eventually("the checking status is saved") { env.take(takeId).checkingFk == passed }
        assertEquals(take.checksum(), env.take(takeId).checksum)
    }

    @Test
    fun `replacing a chapter's chunks gives readers the new layout`() {
        val workbook = env.workbook(project)
        val chapter = chapter(workbook, 1)
        val before = chapter.chunks.blockingGet().count { it.contentType == ContentType.TEXT }
        assertTrue(before > 2, "the verse-by-verse project starts with one row per verse")

        chapter.reset().blockingAwait()
        chapter.addChunk(
            listOf(
                Content(sort = 1, labelKey = "chunk", start = 1, end = 10, text = null, format = null, type = ContentType.TEXT, draftNumber = 2),
                Content(sort = 2, labelKey = "chunk", start = 11, end = 25, text = null, format = null, type = ContentType.TEXT, draftNumber = 2)
            )
        ).blockingAwait()

        env.eventually("the chapter shows the new chunks") {
            chapter.observableChunks.blockingFirst()
                .filter { it.contentType == ContentType.TEXT }
                .map { it.start to it.end } == listOf(1 to 10, 11 to 25)
        }
        assertEquals(2, env.chunkCount(project, 1))
    }
}
