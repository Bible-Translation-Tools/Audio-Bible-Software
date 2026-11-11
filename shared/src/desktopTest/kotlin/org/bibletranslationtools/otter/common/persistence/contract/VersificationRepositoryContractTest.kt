package org.bibletranslationtools.otter.common.persistence.contract

import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.api.persistence.repositories.IVersificationRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class VersificationRepositoryContractTest(mode: ContractMode) : RepositoryContract(mode) {

    private val repo: IVersificationRepository get() = koin.get()

    private fun get(slug: String) = via.maybe({ repo.getVersification(slug) }, { repo.getVersificationSuspend(slug) })

    private fun insert(slug: String, file: File) =
        via.completable({ repo.insertVersification(slug, file) }, { repo.insertVersificationSuspend(slug, file) })

    /** The bundled ULB versification, placed where the repository reads versification files from. */
    private fun installedFile(): File {
        val directory = koin.get<IDirectoryProvider>().versificationDirectory.also { it.mkdirs() }
        return moduleFile("src/commonMain/composeResources/files/versification/ulb_versification.json")
            .copyTo(File(directory, "ulb_versification.json"))
    }

    @Test
    fun `a versification is found by the slug it was inserted under`() {
        insert("ulb", installedFile())

        val versification = assertNotNull(get("ulb"))
        assertEquals(50, versification.getChaptersInBook("gen"))
    }

    @Test
    fun `inserting a slug again replaces its file name`() {
        insert("ulb", File("missing.json"))
        insert("ulb", installedFile())

        assertNotNull(get("ulb"))
        assertEquals("ulb_versification.json", db.versificationDao.fetchVersificationFile("ulb"))
    }

    @Test
    fun `an unknown slug is a failure, not an absent value`() {
        assertFailsWith<NullPointerException> { get("nope") }
    }

    @Test
    fun `a slug whose file is missing is a failure`() {
        insert("ulb", File("missing.json"))

        assertFailsWith<java.io.FileNotFoundException> { get("ulb") }
    }
}
