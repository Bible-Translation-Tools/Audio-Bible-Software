package org.bibletranslationtools.otter.integration

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Deleting a project removes its chapters and their rows through the database's cascades. Desktop
 * once enabled foreign keys only on the connection that opened the database; the deletion runs on
 * another thread's connection, where the cascade didn't fire and the chapters stayed behind.
 */
class ProjectDeletionTest {

    private var env: IntegrationEnvironment? = null

    @AfterTest
    fun tearDown() {
        env?.close()
        env = null
    }

    @Test
    fun `deleting a project leaves none of its chapters or verses behind`() {
        val environment = IntegrationEnvironment.create().also { env = it }
        environment.import("en_ulb.zip")
        val source = environment.sourceEditions().single()
        val project = environment.createProject(
            environment.sourceBook("jud", source.id), environment.language("fr"), deriveProjectFromVerses = true
        )
        val chapterIds = environment.db.collectionDao.fetchAll().filter { it.parentFk == project.id }.map { it.id }

        environment.deleteAllProjects()

        assertEquals(emptyList(), environment.db.collectionDao.fetchAll().filter { it.id in chapterIds || it.parentFk == project.id })
        assertEquals(emptyList(), environment.db.contentDao.fetchAll().filter { it.collectionFk in chapterIds })
    }
}
