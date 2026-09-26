package org.bibletranslationtools.otter.common.domain.resourcecontainer

import org.bibletranslationtools.otter.common.api.persistence.repositories.IEditionFingerprintRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceMetadataRepository
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata

/** The source editions installed on this device. */
class InstalledSourceEditions(
    private val metadataRepository: IResourceMetadataRepository,
    private val fingerprintRepository: IEditionFingerprintRepository
) {
    /** Every installed edition of the source [identifier] in [languageSlug], newest first. */
    suspend fun editionsOf(languageSlug: String, identifier: String): List<ResourceMetadata> =
        metadataRepository.getAllSourcesSuspend()
            .filter { it.language.slug == languageSlug && it.identifier == identifier }
            .sortedWith(EditionOrder.newestFirst)

    /**
     * The installed edition that is the same edition as one with this identity and [fingerprint]
     * (see [isSameEdition]), or null if there is none.
     */
    suspend fun findSameEdition(
        languageSlug: String,
        identifier: String,
        creator: String,
        fingerprint: EditionFingerprint
    ): ResourceMetadata? {
        val incoming = EditionIdentityKey(
            languageSlug, identifier, creator, fingerprint.structureFingerprint, fingerprint.textFingerprint
        )
        return editionsOf(languageSlug, identifier).firstOrNull { edition ->
            val stored = fingerprintRepository.get(edition.id) ?: return@firstOrNull false
            isSameEdition(
                incoming,
                EditionIdentityKey(
                    edition.language.slug, edition.identifier, edition.creator,
                    stored.structureFingerprint, stored.textFingerprint
                )
            )
        }
    }
}
