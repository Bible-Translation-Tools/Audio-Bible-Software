package org.bibletranslationtools.otter.common.persistence.contract

import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceMetadataRepository
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.persistence.entities.LanguageEntity
import org.wycliffeassociates.resourcecontainer.ResourceContainer
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResourceMetadataRepositoryContractTest(mode: ContractMode) : RepositoryContract(mode) {

    private val repo: IResourceMetadataRepository get() = koin.get()

    private lateinit var en: LanguageEntity

    @BeforeTest
    fun seed() {
        en = insertLanguage("en", gateway = 1)
    }

    private fun stored(identifier: String = "ulb", version: String = "1", derivedFrom: ResourceMetadata? = null) =
        insertMetadata(en.id, identifier, version, derivedFromFk = derivedFrom?.id).model(en)

    private fun all() = via.single({ repo.getAll() }, { repo.getAllSuspend() })

    @Test
    fun `insert returns the new id and getAll returns the metadata`() {
        val metadata = metadata(en.id).model(en)

        val id = via.single({ repo.insert(metadata) }, { repo.insertSuspend(metadata) })

        assertEquals(1, id)
        assertEquals(listOf(metadata.copy(id = 1)), all())
    }

    @Test
    fun `exists matches on language, identifier, version and creator`() {
        val ulb = stored()

        fun exists(metadata: ResourceMetadata) = via.single({ repo.exists(metadata) }, { repo.existsSuspend(metadata) })

        assertTrue(exists(ulb.copy(id = 0, title = "another title")))
        assertFalse(exists(ulb.copy(version = "2")))
        assertFalse(exists(ulb.copy(identifier = "udb")))
        assertFalse(exists(ulb.copy(creator = "someone else")))
    }

    @Test
    fun `exists fails with a null pointer exception for a language that is not installed`() {
        val unknown = stored().copy(language = en.model().copy(slug = "zz"))

        assertFailsWith<NullPointerException> {
            via.single({ repo.exists(unknown) }, { repo.existsSuspend(unknown) })
        }
    }

    @Test
    fun `exists with a predicate tests every stored metadata`() {
        stored("ulb")
        stored("udb")

        fun exists(predicate: (ResourceMetadata) -> Boolean) =
            via.single({ repo.exists(predicate) }, { repo.existsSuspend(predicate) })

        assertTrue(exists { it.identifier == "udb" })
        assertFalse(exists { it.identifier == "tn" })
    }

    @Test
    fun `get returns the stored row for a metadata that has no id yet`() {
        val ulb = stored()

        assertEquals(ulb, via.single({ repo.get(ulb.copy(id = 0)) }, { repo.getSuspend(ulb.copy(id = 0)) }))
    }

    @Test
    fun `get fails with a null pointer exception when nothing matches`() {
        val missing = stored().copy(version = "2")

        assertFailsWith<NullPointerException> {
            via.single({ repo.get(missing) }, { repo.getSuspend(missing) })
        }
    }

    @Test
    fun `sources and derivatives are told apart by the derived-from link`() {
        val source = stored("ulb")
        val derived = stored("ulb", version = "2", derivedFrom = source)
        stored("udb")

        assertEquals(
            listOf("ulb" to "1", "udb" to "1"),
            via.single({ repo.getAllSources() }, { repo.getAllSourcesSuspend() }).map { it.identifier to it.version }
        )
        assertEquals(
            listOf(derived),
            via.single({ repo.getAllDerivatives(source) }, { repo.getAllDerivativesSuspend(source) })
        )
    }

    private fun sourceOf(metadata: ResourceMetadata) =
        via.maybe({ repo.getSource(metadata) }, { repo.getSourceSuspend(metadata) })

    @Test
    fun `getSource returns the source of a derived metadata, and nothing for a source`() {
        val source = stored("ulb")
        val derived = stored("ulb", version = "2", derivedFrom = source)

        assertEquals(source, sourceOf(derived))
        assertNull(sourceOf(source))
    }

    @Test
    fun `updateSource sets the source and clears it`() {
        val source = stored("ulb")
        val derived = stored("udb")

        via.completable({ repo.updateSource(derived, source) }, { repo.updateSourceSuspend(derived, source) })
        assertEquals(source, sourceOf(derived))

        via.completable({ repo.updateSource(derived, null) }, { repo.updateSourceSuspend(derived, null) })
        assertNull(sourceOf(derived))
    }

    @Test
    fun `update rewrites the row and keeps its source`() {
        val source = stored("ulb")
        val derived = stored("ulb", version = "2", derivedFrom = source)
        val changed = derived.copy(title = "New title")

        via.completable({ repo.update(changed) }, { repo.updateSuspend(changed) })

        assertEquals(changed, all().single { it.id == derived.id })
        assertEquals(source, sourceOf(derived))
    }

    @Test
    fun `update fails with a null pointer exception for an id that is not stored`() {
        val unknown = stored().copy(id = 99)

        assertFailsWith<NullPointerException> {
            via.completable({ repo.update(unknown) }, { repo.updateSuspend(unknown) })
        }
    }

    @Test
    fun `update from a resource container takes the container's metadata and keeps the row's path`() {
        val stored = stored()
        fun container() = ResourceContainer.load(
            moduleFile("src/desktopIntegrationTest/resources/resource-containers/en_ulb.zip")
        )
        val expected = container().use { it.manifest.dublinCore }

        via.completable({ repo.update(stored, container()) }, { repo.updateSuspend(stored, container()) })

        val row = all().single()
        assertEquals(expected.title, row.title)
        assertEquals(expected.version, row.version)
        assertEquals(stored.path, row.path)
        assertEquals(stored.id, row.id)
    }

    @Test
    fun `a link is seen from both ends until it is removed`() {
        val ulb = stored("ulb")
        val tn = stored("tn")

        fun linked(metadata: ResourceMetadata) = via.single({ repo.getLinked(metadata) }, { repo.getLinkedSuspend(metadata) })

        via.completable({ repo.addLink(ulb, tn) }, { repo.addLinkSuspend(ulb, tn) })
        assertEquals(listOf(tn), linked(ulb))
        assertEquals(listOf(ulb), linked(tn))

        via.completable({ repo.removeLink(ulb, tn) }, { repo.removeLinkSuspend(ulb, tn) })
        assertTrue(linked(ulb).isEmpty())
    }

    @Test
    fun `delete removes the row`() {
        val ulb = stored()

        via.completable({ repo.delete(ulb) }, { repo.deleteSuspend(ulb) })

        assertTrue(all().isEmpty())
    }
}
