package org.bibletranslationtools.otter.integration

import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.otter.common.data.workbook.SaveResult
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An import and a stream of workbook writes at the same time. Before the write queue, each ran on
 * its own connection whenever it liked, and with no busy timeout one of them could fail at once
 * with SQLITE_BUSY.
 */
class WriteQueueStressTest {

    private var env: IntegrationEnvironment? = null

    @AfterTest
    fun tearDown() {
        env?.close()
    }

    @Test
    fun `an import and workbook writes run together without errors`() {
        val environment = IntegrationEnvironment.create().also { env = it }
        environment.import("en_ulb.zip")
        val source = environment.sourceEditions().single()
        val project = environment.createProject(environment.sourceBook("jud", source.id), environment.language("fr"), deriveProjectFromVerses = true)
        val workbook = environment.workbook(project)
        val chunks = runBlocking { environment.chapter(workbook, 1).chunks() }.filter { it.label == "verse" }
        val newerEdition = environment.newerEdition("en_ulb.zip")

        val pool = Executors.newFixedThreadPool(2)
        val importing = pool.submit { environment.import(newerEdition) }
        val recording = pool.submit<List<SaveResult<*>>> {
            runBlocking {
                chunks.take(20).mapIndexed { i, chunk ->
                    chunk.audio.insertTake(environment.newTake(project, 1, "stress_v${i + 1}_t1.wav"))
                }
            }
        }
        importing.get(5, TimeUnit.MINUTES)
        val results = recording.get(1, TimeUnit.MINUTES)
        pool.shutdown()

        assertTrue(results.all { it is SaveResult.Saved }, "a recording was not saved: ${results.filterNot { it is SaveResult.Saved }}")
        assertEquals(20, environment.db.takeDao.fetchAll().count { it.filename.startsWith("stress_") })
    }
}
