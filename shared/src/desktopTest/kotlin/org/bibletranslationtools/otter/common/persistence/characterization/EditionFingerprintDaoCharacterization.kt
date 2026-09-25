package org.bibletranslationtools.otter.common.persistence.characterization

import org.bibletranslationtools.otter.common.persistence.entities.EditionChapterEntity
import org.bibletranslationtools.otter.common.persistence.entities.EditionFingerprintEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Characterizes the edition fingerprint queries (schema v15) on ResourceMetadataDao and EditionChapterDao. */
abstract class EditionFingerprintDaoCharacterization : AbstractDatabaseCharacterizationTest() {

    private val fingerprint = EditionFingerprintEntity("eng", "structure", "text")

    private fun chapter(dublinCoreFk: Int, slug: String) = EditionChapterEntity(dublinCoreFk, slug, "s-$slug", "t-$slug")

    @Test
    fun `a new row has no fingerprint`() {
        val source = insertMetadata(insertLanguage("en").id)

        assertEquals(EditionFingerprintEntity(null, null, null), db.resourceMetadataDao.fetchEditionFingerprint(source.id))
    }

    @Test
    fun `setEditionFingerprint round-trips`() {
        val source = insertMetadata(insertLanguage("en").id)

        db.resourceMetadataDao.setEditionFingerprint(source.id, fingerprint)

        assertEquals(fingerprint, db.resourceMetadataDao.fetchEditionFingerprint(source.id))
    }

    @Test
    fun `fetchEditionFingerprint is null for a missing row`() {
        assertNull(db.resourceMetadataDao.fetchEditionFingerprint(999))
    }

    /** Re-mapping metadata from a manifest (update) must not clear a fingerprint. */
    @Test
    fun `update leaves the fingerprint alone`() {
        val source = insertMetadata(insertLanguage("en").id)
        db.resourceMetadataDao.setEditionFingerprint(source.id, fingerprint)

        db.resourceMetadataDao.update(source.copy(version = "2"))

        assertEquals(fingerprint, db.resourceMetadataDao.fetchEditionFingerprint(source.id))
    }

    @Test
    fun `fetchSourceIdsWithoutFingerprint lists only unfingerprinted sources`() {
        val language = insertLanguage("en").id
        val fingerprinted = insertMetadata(language, identifier = "ulb")
        val bare = insertMetadata(language, identifier = "ust")
        insertMetadata(insertLanguage("fr").id, identifier = "ulb", derivedFromFk = fingerprinted.id)
        db.resourceMetadataDao.setEditionFingerprint(fingerprinted.id, fingerprint)

        assertEquals(listOf(bare.id), db.resourceMetadataDao.fetchSourceIdsWithoutFingerprint())
    }

    @Test
    fun `replaceForEdition replaces every chapter of that edition only`() {
        val language = insertLanguage("en").id
        val a = insertMetadata(language, identifier = "ulb")
        val b = insertMetadata(language, identifier = "ust")
        db.editionChapterDao.replaceForEdition(a.id, listOf(chapter(a.id, "gen_1"), chapter(a.id, "gen_2")))
        db.editionChapterDao.replaceForEdition(b.id, listOf(chapter(b.id, "gen_1")))

        db.editionChapterDao.replaceForEdition(a.id, listOf(chapter(a.id, "exo_1")))

        assertEquals(listOf(chapter(a.id, "exo_1")), db.editionChapterDao.fetchForEdition(a.id))
        assertEquals(listOf(chapter(b.id, "gen_1")), db.editionChapterDao.fetchForEdition(b.id))
    }
}
