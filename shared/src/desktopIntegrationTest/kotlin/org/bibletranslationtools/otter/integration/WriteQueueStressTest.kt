package org.bibletranslationtools.otter.integration

import org.bibletranslationtools.otter.common.data.primitives.MimeType
import org.bibletranslationtools.otter.common.data.workbook.Take
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

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
        val chunks = workbook.target.chapters.filter { it.sort == 1 }.blockingFirst()
            .chunks.blockingGet().filter { it.label == "verse" }
        val newerEdition = environment.newerEdition("en_ulb.zip")

        val pool = Executors.newFixedThreadPool(2)
        val importing = pool.submit { environment.import(newerEdition) }
        val recording = pool.submit {
            chunks.take(20).forEachIndexed { i, chunk ->
                val file = environment.projectDirectory(project).resolve(".apps/orature/takes/c01/stress_v${i + 1}_t1.wav")
                file.parentFile.mkdirs()
                file.writeText("take ${i + 1}")
                chunk.audio.insertTake(Take(file.name, file, 1, MimeType.WAV, LocalDate.now()))
            }
        }
        importing.get(5, TimeUnit.MINUTES)
        recording.get(1, TimeUnit.MINUTES)
        pool.shutdown()

        environment.eventually("every recorded take is saved", timeoutMillis = 30_000) {
            environment.db.takeDao.fetchAll().count { it.filename.startsWith("stress_") } == 20
        }
        assertEquals(20, environment.db.takeDao.fetchAll().count { it.filename.startsWith("stress_") })
    }
}
