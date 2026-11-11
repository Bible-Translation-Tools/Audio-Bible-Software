package org.bibletranslationtools.otter.common.persistence.contract

import org.bibletranslationtools.otter.common.api.persistence.repositories.ILanguageRepository
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.workbook.Translation
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanguageRepositoryContractTest(mode: ContractMode) : RepositoryContract(mode) {

    private val repo: ILanguageRepository get() = koin.get()

    private fun lang(slug: String, gateway: Boolean = false, region: String = "region") =
        Language(slug, slug.uppercase(), slug.uppercase(), "ltr", gateway, region)

    private fun all() = via.single({ repo.getAll() }, { repo.getAllSuspend() })

    @Test
    fun `insert returns the new id and getAll returns the language`() {
        val id = via.single({ repo.insert(lang("en", gateway = true)) }, { repo.insertSuspend(lang("en", gateway = true)) })

        assertEquals(1, id)
        assertEquals(listOf(lang("en", gateway = true).copy(id = 1)), all())
    }

    @Test
    fun `insertAll returns one id per language, in order`() {
        val languages = listOf(lang("en"), lang("fr"), lang("es"))

        val ids = via.single({ repo.insertAll(languages) }, { repo.insertAllSuspend(languages) })

        assertEquals(listOf(1, 2, 3), ids)
        assertEquals(listOf("en", "fr", "es"), all().map { it.slug })
    }

    @Test
    fun `upsertAll updates a language matched by slug and inserts a new one`() {
        insertLanguage("en")
        val languages = listOf(lang("en").copy(name = "English"), lang("fr"))

        via.completable({ repo.upsertAll(languages) }, { repo.upsertAllSuspend(languages) })

        assertEquals(listOf("en" to "English", "fr" to "FR"), all().map { it.slug to it.name })
    }

    @Test
    fun `updateRegions changes the region of a language matched by slug, and nothing else`() {
        insertLanguage("en")
        val languages = listOf(lang("en", region = "Europe").copy(name = "ignored"), lang("zz", region = "nowhere"))

        via.completable({ repo.updateRegions(languages) }, { repo.updateRegionsSuspend(languages) })

        assertEquals(listOf(Triple("en", "EN", "Europe")), all().map { Triple(it.slug, it.name, it.region) })
    }

    @Test
    fun `getBySlug returns the language`() {
        val en = insertLanguage("en", gateway = 1).model()

        assertEquals(en, via.single({ repo.getBySlug("en") }, { repo.getBySlugSuspend("en") }))
    }

    @Test
    fun `getBySlug fails with a null pointer exception for an unknown slug`() {
        assertFailsWith<NullPointerException> {
            via.single({ repo.getBySlug("zz") }, { repo.getBySlugSuspend("zz") })
        }
    }

    @Test
    fun `getGateway and getTargets split languages by the gateway flag`() {
        insertLanguage("en", gateway = 1)
        insertLanguage("fr", gateway = 0)

        assertEquals(listOf("en"), via.single({ repo.getGateway() }, { repo.getGatewaySuspend() }).map { it.slug })
        assertEquals(listOf("fr"), via.single({ repo.getTargets() }, { repo.getTargetsSuspend() }).map { it.slug })
    }

    @Test
    fun `getAvailableGatewaySources leaves out a gateway language with no bundled source`() {
        insertLanguage("en", gateway = 1)
        insertLanguage("zz", gateway = 1)

        val available = via.single({ repo.getAvailableGatewaySources() }, { repo.getAvailableGatewaySourcesSuspend() })

        assertEquals(listOf("en"), available.map { it.slug })
    }

    @Test
    fun `update rewrites the row`() {
        val en = insertLanguage("en").model()

        via.completable({ repo.update(en.copy(name = "English")) }, { repo.updateSuspend(en.copy(name = "English")) })

        assertEquals("English", all().single().name)
    }

    @Test
    fun `delete removes the row`() {
        val en = insertLanguage("en").model()

        via.completable({ repo.delete(en) }, { repo.deleteSuspend(en) })

        assertTrue(all().isEmpty())
    }

    private fun translation(modified: LocalDateTime? = null) =
        Translation(insertLanguage("en", gateway = 1).model(), insertLanguage("fr").model(), modified)

    private fun insert(translation: Translation) =
        via.single({ repo.insertTranslation(translation) }, { repo.insertTranslationSuspend(translation) })

    private fun allTranslations() = via.single({ repo.getAllTranslations() }, { repo.getAllTranslationsSuspend() })

    @Test
    fun `insertTranslation returns the new id, and getTranslation finds it by its languages`() {
        val translation = translation()

        val id = insert(translation)

        assertEquals(1, id)
        assertEquals(
            translation.copy(id = 1),
            via.single(
                { repo.getTranslation(translation.source, translation.target) },
                { repo.getTranslationSuspend(translation.source, translation.target) }
            )
        )
    }

    @Test
    fun `insertTranslation for a pair already there updates its timestamp and returns the same id`() {
        val translation = translation()
        val id = insert(translation)
        val later = LocalDateTime.of(2026, 1, 2, 3, 4, 5)

        assertEquals(id, insert(translation.copy(modifiedTs = later, sourceRate = 2.0)))

        val stored = allTranslations().single()
        assertEquals(later, stored.modifiedTs)
        assertEquals(1.0, stored.sourceRate, "only the timestamp is taken from the second insert")
    }

    @Test
    fun `getTranslation fails with a null pointer exception when there is none`() {
        val translation = translation()

        assertFailsWith<NullPointerException> {
            via.single(
                { repo.getTranslation(translation.source, translation.target) },
                { repo.getTranslationSuspend(translation.source, translation.target) }
            )
        }
    }

    @Test
    fun `updateTranslation rewrites the rates`() {
        val translation = translation()
        val stored = translation.copy(id = insert(translation), sourceRate = 1.5, targetRate = 0.5)

        via.completable({ repo.updateTranslation(stored) }, { repo.updateTranslationSuspend(stored) })

        assertEquals(listOf(stored), allTranslations())
    }

    @Test
    fun `deleteTranslation removes the row`() {
        val translation = translation()
        val stored = translation.copy(id = insert(translation))

        via.completable({ repo.deleteTranslation(stored) }, { repo.deleteTranslationSuspend(stored) })

        assertTrue(allTranslations().isEmpty())
        assertFalse(all().isEmpty(), "the languages stay")
        assertNull(db.translationDao.fetch(stored.source.id, stored.target.id))
    }
}
