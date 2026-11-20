package org.bibletranslationtools.otter.common.initialization

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.bibletranslationtools.otter.common.api.persistence.IAppDirectories
import org.bibletranslationtools.otter.common.api.persistence.repositories.IInstalledEntityRepository
import org.bibletranslationtools.otter.common.persistence.database.dao.DaoProvider
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReconcileEditionLabelsTest {

    private val logs: File = Files.createTempDirectory("logs").toFile()
    private val report = File(logs, EDITION_LABEL_REPORT_FILE)

    private val daoProvider = mockk<DaoProvider> {
        every { resourceMetadataDao.fetchAll() } returns emptyList()
    }
    private val directories = mockk<IAppDirectories> {
        every { logsDirectory } returns logs
    }
    private val installed = mockk<IInstalledEntityRepository>(relaxUnitFun = true) {
        every { getInstalledVersion(any()) } returns null
    }
    private val initializer = ReconcileEditionLabels(daoProvider, directories, installed)

    @AfterTest
    fun cleanup() {
        logs.deleteRecursively()
    }

    @Test
    fun `the first run writes the report and records the version`() = runTest {
        initializer.execSuspend {}

        assertTrue(report.readText().contains("\"findings\": []"))
        verify(exactly = 1) { installed.install(initializer) }
    }

    @Test
    fun `the current version does nothing`() = runTest {
        every { installed.getInstalledVersion(any()) } returns initializer.version

        initializer.execSuspend {}

        verify(exactly = 0) { daoProvider.resourceMetadataDao }
        assertFalse(report.exists())
    }

    /** It never fails initialization: the error is logged and it runs again next launch. */
    @Test
    fun `a failure is not thrown and the version is not recorded`() = runTest {
        every { daoProvider.resourceMetadataDao.fetchAll() } throws IllegalStateException("database is locked")

        initializer.execSuspend {}

        assertFalse(report.exists())
        verify(exactly = 0) { installed.install(any()) }
    }
}
