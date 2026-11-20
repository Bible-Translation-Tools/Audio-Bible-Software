package org.bibletranslationtools.otter.common.persistence.repositories

import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.bibletranslationtools.otter.common.persistence.database.dao.DaoProvider
import org.bibletranslationtools.otter.common.api.persistence.repositories.ICollectionRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IContentRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceMetadataRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceRepository
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import org.bibletranslationtools.otter.common.persistence.database.dao.ContentTypeDao
import org.bibletranslationtools.otter.common.persistence.database.dao.CollectionDao
import org.bibletranslationtools.otter.common.persistence.database.dao.ContentDao
import org.bibletranslationtools.otter.common.persistence.database.dao.ResourceMetadataDao
import org.bibletranslationtools.otter.common.persistence.database.dao.ResourceLinkDao
import org.bibletranslationtools.otter.common.persistence.database.dao.LanguageDao
import org.wycliffeassociates.resourcecontainer.ResourceContainer
import org.bibletranslationtools.otter.common.collections.OtterTree
import org.bibletranslationtools.otter.common.data.primitives.CollectionOrContent
import org.bibletranslationtools.otter.common.persistence.database.dao.CheckingStatusDao
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ResourceContainerRepositoryTest {

    private val db = mockk<DaoProvider>()
    private val collectionRepository = mockk<ICollectionRepository>()
    private val contentRepository = mockk<IContentRepository>()
    private val resourceRepository = mockk<IResourceRepository>()
    private val resourceMetadataRepository = mockk<IResourceMetadataRepository>()
    
    private val collectionDao = mockk<CollectionDao>()
    private val contentDao = mockk<ContentDao>()
    private val contentTypeDao = mockk<ContentTypeDao>()
    private val resourceMetadataDao = mockk<ResourceMetadataDao>()
    private val languageDao = mockk<LanguageDao>()
    private val resourceLinkDao = mockk<ResourceLinkDao>()
    private val checkingStatusDao = mockk<CheckingStatusDao>()

    private lateinit var repository: ResourceContainerRepository

    @BeforeTest
    fun setup() {
        every { db.collectionDao } returns collectionDao
        every { db.contentDao } returns contentDao
        every { db.contentTypeDao } returns contentTypeDao
        every { db.resourceMetadataDao } returns resourceMetadataDao
        every { db.languageDao } returns languageDao
        every { db.resourceLinkDao } returns resourceLinkDao
        every { db.checkingStatusDao } returns checkingStatusDao

        repository = ResourceContainerRepository(
            db,
            collectionRepository,
            contentRepository,
            resourceRepository,
            resourceMetadataRepository
        )
    }

    /** A failure inside the transaction comes back as a result, and the container is closed. */
    @Test
    fun `an import that fails in the transaction returns a result and closes the container`() = runTest {
        val rc = mockk<ResourceContainer>(relaxed = true)
        val rcTree = mockk<OtterTree<CollectionOrContent>>()
        every { db.transaction(any()) } answers { firstArg<() -> Unit>().invoke() }
        every { languageDao.fetchBySlug("en") } returns null

        val result = repository.importResourceContainerSuspend(rc, rcTree, "en")

        assertEquals(ImportResult.LOAD_RC_ERROR, result)
        verify(exactly = 1) { rc.close() }
    }

}
