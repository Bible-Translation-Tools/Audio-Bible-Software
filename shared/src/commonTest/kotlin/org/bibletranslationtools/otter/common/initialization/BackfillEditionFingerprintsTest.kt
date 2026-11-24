package org.bibletranslationtools.otter.common.initialization

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.bibletranslationtools.otter.common.api.persistence.repositories.IEditionFingerprintRepository
import org.bibletranslationtools.otter.common.domain.project.importer.EditionFingerprinter
import org.bibletranslationtools.otter.common.domain.resourcecontainer.EditionFingerprint
import org.bibletranslationtools.otter.common.persistence.database.dao.DaoProvider
import org.bibletranslationtools.otter.common.persistence.entities.ResourceMetadataEntity
import java.io.File
import kotlin.test.Test

class BackfillEditionFingerprintsTest {

    private val first = EditionFingerprint("eng", "structure-1", "text-1", emptyList())
    private val second = EditionFingerprint("eng", "structure-2", "text-2", emptyList())

    private val daoProvider = mockk<DaoProvider> {
        every { resourceMetadataDao.fetchById(1) } returns source("/sources/en_ulb")
        every { resourceMetadataDao.fetchById(2) } returns source("/sources/es_ulb")
    }
    private val fingerprints = mockk<IEditionFingerprintRepository>(relaxUnitFun = true) {
        coEvery { sourcesWithoutFingerprint() } returns listOf(1, 2)
    }
    private val fingerprinter = mockk<EditionFingerprinter> {
        coEvery { fingerprintSuspend(File("/sources/en_ulb")) } returns first
        coEvery { fingerprintSuspend(File("/sources/es_ulb")) } returns second
    }
    private val initializer = BackfillEditionFingerprints(daoProvider, fingerprints, fingerprinter)

    private fun source(path: String): ResourceMetadataEntity = mockk {
        every { this@mockk.path } returns path
        every { identifier } returns "ulb"
        every { version } returns "1"
    }

    @Test
    fun `every source without a fingerprint gets one`() = runTest {
        initializer.exec {}

        coVerify(exactly = 1) { fingerprints.save(1, first) }
        coVerify(exactly = 1) { fingerprints.save(2, second) }
    }

    @Test
    fun `with nothing to fingerprint nothing is read`() = runTest {
        coEvery { fingerprints.sourcesWithoutFingerprint() } returns emptyList()

        initializer.exec {}

        coVerify(exactly = 0) { fingerprinter.fingerprintSuspend(any<File>()) }
    }

    /** A source that can't be read is tried again next launch; it never fails initialization. */
    @Test
    fun `a source that cannot be fingerprinted is skipped and the rest go on`() = runTest {
        coEvery { fingerprinter.fingerprintSuspend(File("/sources/en_ulb")) } throws IllegalStateException("unreadable")

        initializer.exec {}

        coVerify(exactly = 0) { fingerprints.save(1, any()) }
        coVerify(exactly = 1) { fingerprints.save(2, second) }
    }

    @Test
    fun `a source whose row is gone is skipped`() = runTest {
        every { daoProvider.resourceMetadataDao.fetchById(1) } returns null

        initializer.exec {}

        coVerify(exactly = 0) { fingerprints.save(1, any()) }
        coVerify(exactly = 1) { fingerprints.save(2, second) }
    }
}
