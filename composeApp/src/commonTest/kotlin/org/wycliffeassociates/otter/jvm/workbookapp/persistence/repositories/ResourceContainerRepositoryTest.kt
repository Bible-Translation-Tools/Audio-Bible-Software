package org.wycliffeassociates.otter.jvm.workbookapp.persistence.repositories

import io.mockk.*
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.test.runTest
import org.bibletranslationtools.otter.common.api.persistence.AppDatabase
import org.bibletranslationtools.otter.common.api.persistence.repositories.ICollectionRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IContentRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceMetadataRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceRepository
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import org.bibletranslationtools.otter.common.persistence.database.daos.ContentTypeDao
import org.bibletranslationtools.otter.common.persistence.database.daos.CollectionDao
import org.bibletranslationtools.otter.common.persistence.database.daos.ContentDao
import org.bibletranslationtools.otter.common.persistence.database.daos.ResourceMetadataDao
import org.bibletranslationtools.otter.common.persistence.database.daos.ResourceLinkDao
import org.bibletranslationtools.otter.common.persistence.database.daos.LanguageDao
import org.wycliffeassociates.resourcecontainer.ResourceContainer
import org.wycliffeassociates.otter.common.collections.OtterTree
import org.bibletranslationtools.otter.common.data.primitives.CollectionOrContent
import org.bibletranslationtools.otter.common.persistence.database.daos.CheckingStatusDao
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ResourceContainerRepositoryTest {

    private val db = mockk<AppDatabase>()
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
        mockkStatic(Schedulers::class)
        every { Schedulers.io() } returns Schedulers.trampoline()

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

    @Test
    fun testImportResourceContainerSuspend() = runTest {
        val rc = mockk<ResourceContainer>(relaxed = true)
        val rcTree = mockk<OtterTree<CollectionOrContent>>()
        val languageSlug = "en"
        
        // Mocking complex import logic would be too much for a wrapper test
        // Instead, we mock the RxJava method that the suspend version calls.
        // Wait, the repository is what we are testing.
        // We should mock its dependencies and let the method run.
        
        // However, importResourceContainer is very complex and relies on many static 
        // extensions and external classes. 
        // For the sake of "wrapper" verification, we can use a spy.
        
        val spy = spyk(repository)
        every { spy.importResourceContainer(any(), any(), any()) } returns Single.just(ImportResult.SUCCESS)
        
        val result = spy.importResourceContainerSuspend(rc, rcTree, languageSlug)
        assertEquals(ImportResult.SUCCESS, result)
    }

    @Test
    fun testUpdateContentSuspend() = runTest {
        val rc = mockk<ResourceContainer>(relaxed = true)
        val rcTree = mockk<OtterTree<CollectionOrContent>>()
        
        val spy = spyk(repository)
        every { spy.updateContent(any(), any()) } returns Single.just(ImportResult.SUCCESS)
        
        val result = spy.updateContentSuspend(rc, rcTree)
        assertEquals(ImportResult.SUCCESS, result)
    }
}
