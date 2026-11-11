package org.bibletranslationtools.otter.common.persistence.contract

import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookDescriptorRepository
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.persistence.entities.CollectionEntity
import java.io.IOException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkbookDescriptorRepositoryContractTest(mode: ContractMode) : RepositoryContract(mode) {

    private val repo: IWorkbookDescriptorRepository get() = koin.get()

    private lateinit var source: CollectionEntity
    private lateinit var target: CollectionEntity
    private var descriptorId = 0

    @BeforeTest
    fun seed() {
        val chain = insertProjectChain()
        source = chain.project
        val french = insertLanguage("fr")
        val derived = insertMetadata(french.id, derivedFromFk = chain.metadata.id)
        target = insertCollection(derived.id, "gen", sourceFk = source.id)
        descriptorId = db.workbookDescriptorDao.insert(workbookDescriptor(source.id, target.id, ProjectMode.NARRATION))
    }

    private fun all() = via.single({ repo.getAll(false) }, { repo.getAllSuspend(false) })

    @Test
    fun `getAll returns each descriptor with its source, target and mode`() {
        val descriptor = all().single()

        assertEquals(descriptorId, descriptor.id)
        assertEquals(source.id, descriptor.sourceCollection.id)
        assertEquals(target.id, descriptor.targetCollection.id)
        assertEquals("fr", descriptor.targetCollection.resourceContainer?.language?.slug)
        assertEquals(ProjectMode.NARRATION, descriptor.mode)
        assertEquals(false, descriptor.hasSourceAudio, "source audio is not looked up unless asked for")
    }

    @Test
    fun `getAll leaves out a descriptor whose project is gone`() {
        db.workbookDescriptorDao.insert(workbookDescriptor(source.id, target.id))
        db.collectionDao.delete(target)

        assertTrue(all().isEmpty())
    }

    private fun byId(id: Int) = via.maybe({ repo.getById(id) }, { repo.getByIdSuspend(id) })

    @Test
    fun `getById returns the descriptor, and nothing for an unknown id`() {
        // It opens the source's resource container to look for source audio, so there has to be one.
        val container = moduleFile("src/desktopIntegrationTest/resources/resource-containers/en_ulb.zip")
        db.resourceMetadataDao.update(db.resourceMetadataDao.fetchById(source.dublinCoreFk!!)!!.copy(path = container.path))

        val found = assertNotNull(byId(descriptorId))

        assertEquals(source.id, found.sourceCollection.id)
        assertEquals(target.id, found.targetCollection.id)
        assertEquals(false, found.hasSourceAudio)
        assertNull(byId(99))
    }

    @Test
    fun `getById fails with an IO exception when the source's resource container is missing`() {
        assertFailsWith<IOException> { byId(descriptorId) }
    }

    @Test
    fun `delete removes the listed descriptors`() {
        val descriptors = all()

        via.completable({ repo.delete(descriptors) }, { repo.deleteSuspend(descriptors) })

        assertTrue(all().isEmpty())
    }
}
