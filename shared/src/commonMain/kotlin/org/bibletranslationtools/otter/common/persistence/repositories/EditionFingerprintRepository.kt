package org.bibletranslationtools.otter.common.persistence.repositories

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.api.persistence.repositories.IEditionFingerprintRepository
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ChapterFingerprint
import org.bibletranslationtools.otter.common.domain.resourcecontainer.EditionFingerprint
import org.bibletranslationtools.otter.common.persistence.database.dao.DaoProvider
import org.bibletranslationtools.otter.common.persistence.entities.EditionChapterEntity
import org.bibletranslationtools.otter.common.persistence.entities.EditionFingerprintEntity

class EditionFingerprintRepository(private val database: DaoProvider) : IEditionFingerprintRepository {

    override suspend fun save(metadataId: Int, fingerprint: EditionFingerprint) =
        withContext(Dispatchers.IO) { database.storeEditionFingerprint(metadataId, fingerprint) }

    override suspend fun get(metadataId: Int): EditionFingerprint? =
        withContext(Dispatchers.IO) { database.loadEditionFingerprint(metadataId) }

    override suspend fun sourcesWithoutFingerprint(): List<Int> =
        withContext(Dispatchers.IO) { database.resourceMetadataDao.fetchSourceIdsWithoutFingerprint() }
}

/**
 * Writes [fingerprint] for resource metadata row [metadataId], replacing any earlier one. Joins the
 * caller's transaction when there is one, so an import can store it atomically with the source.
 */
internal fun DaoProvider.storeEditionFingerprint(metadataId: Int, fingerprint: EditionFingerprint) {
    transaction {
        resourceMetadataDao.setEditionFingerprint(
            metadataId,
            EditionFingerprintEntity(
                detectedVersification = fingerprint.detectedVersification,
                structureFingerprint = fingerprint.structureFingerprint,
                textFingerprint = fingerprint.textFingerprint
            )
        )
        editionChapterDao.replaceForEdition(
            metadataId,
            fingerprint.chapters.map {
                EditionChapterEntity(metadataId, it.chapterSlug, it.structureHash, it.textHash)
            }
        )
    }
}

internal fun DaoProvider.loadEditionFingerprint(metadataId: Int): EditionFingerprint? {
    val columns = resourceMetadataDao.fetchEditionFingerprint(metadataId) ?: return null
    val structure = columns.structureFingerprint ?: return null
    val text = columns.textFingerprint ?: return null
    return EditionFingerprint(
        detectedVersification = columns.detectedVersification,
        structureFingerprint = structure,
        textFingerprint = text,
        chapters = editionChapterDao.fetchForEdition(metadataId).map {
            ChapterFingerprint(it.chapterSlug, it.structureHash, it.textHash)
        }
    )
}
