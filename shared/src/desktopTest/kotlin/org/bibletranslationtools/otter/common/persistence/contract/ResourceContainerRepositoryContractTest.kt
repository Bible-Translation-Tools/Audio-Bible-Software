package org.bibletranslationtools.otter.common.persistence.contract

import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceContainerRepository
import org.bibletranslationtools.otter.common.collections.OtterTree
import org.bibletranslationtools.otter.common.collections.OtterTreeNode
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.CollectionOrContent
import org.bibletranslationtools.otter.common.data.primitives.Content
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.domain.resourcecontainer.DeleteResult
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import org.wycliffeassociates.resourcecontainer.ResourceContainer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResourceContainerRepositoryContractTest(mode: ContractMode) : RepositoryContract(mode) {

    private val repo: IResourceContainerRepository get() = koin.get()

    /** The English ULB container. The repository closes what it is given, so each call loads its own. */
    private fun container(): ResourceContainer {
        val file = File(root, "en_ulb.zip")
        if (!file.exists()) {
            moduleFile("src/desktopIntegrationTest/resources/resource-containers/en_ulb.zip").copyTo(file)
        }
        return ResourceContainer.load(file)
    }

    /** bible > gen > chapter 1 > one verse. */
    private fun tree(): OtterTree<CollectionOrContent> {
        val chapter = OtterTree<CollectionOrContent>(Collection(1, "gen-1", "chapter", "1", null))
        chapter.addChild(OtterTreeNode(Content(1, "verse", 1, 1, null, "In the beginning", "text/usfm", ContentType.TEXT, 1)))
        val book = OtterTree<CollectionOrContent>(Collection(1, "gen", "project", "Genesis", null))
        book.addChild(chapter)
        return OtterTree<CollectionOrContent>(Collection(0, "bible", "bundle", "Bible", null)).also { it.addChild(book) }
    }

    private fun import(language: String = "en") = via.single(
        { repo.importResourceContainer(container(), tree(), language) },
        { repo.importResourceContainerSuspend(container(), tree(), language) }
    )

    private fun remove() = via.single(
        { repo.removeResourceContainer(container()) },
        { repo.removeResourceContainerSuspend(container()) }
    )

    @Test
    fun `import stores the container's metadata and its tree`() {
        insertLanguage("en", gateway = 1)

        assertEquals(ImportResult.SUCCESS, import())

        val metadata = db.resourceMetadataDao.fetchAll().single()
        assertEquals("ulb", metadata.identifier)
        assertEquals(File(root, "en_ulb.zip").canonicalFile, File(metadata.path).canonicalFile)
        val collections = db.collectionDao.fetchAll()
        assertEquals(listOf("bible", "gen", "gen-1"), collections.map { it.slug })
        assertEquals(listOf(null, collections[0].id, collections[1].id), collections.map { it.parentFk })
        assertTrue(collections.all { it.dublinCoreFk == metadata.id })
        assertEquals(listOf(collections[2].id), db.contentDao.fetchAll().map { it.collectionFk })
    }

    @Test
    fun `import for a language that is not installed reports a load error and stores nothing`() {
        assertEquals(ImportResult.LOAD_RC_ERROR, import("zz"))

        assertTrue(db.resourceMetadataDao.fetchAll().isEmpty())
        assertTrue(db.collectionDao.fetchAll().isEmpty())
    }

    @Test
    fun `remove deletes an imported container's metadata and collections`() {
        insertLanguage("en", gateway = 1)
        import()

        assertEquals(DeleteResult.SUCCESS, remove())

        assertTrue(db.resourceMetadataDao.fetchAll().isEmpty())
        assertTrue(db.collectionDao.fetchAll().isEmpty())
        assertTrue(db.contentDao.fetchAll().isEmpty())
    }

    @Test
    fun `remove reports not found for a container that was never imported`() {
        assertEquals(DeleteResult.NOT_FOUND, remove())
    }

    @Test
    fun `remove refuses while something is derived from the container`() {
        val english = insertLanguage("en", gateway = 1)
        import()
        val source = db.resourceMetadataDao.fetchAll().single()
        insertMetadata(english.id, version = "derived", derivedFromFk = source.id)

        assertEquals(DeleteResult.DEPENDENCY_EXISTS, remove())

        assertEquals(3, db.collectionDao.fetchAll().size)
    }
}
