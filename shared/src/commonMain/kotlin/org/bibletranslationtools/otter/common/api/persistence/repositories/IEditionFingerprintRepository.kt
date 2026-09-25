package org.bibletranslationtools.otter.common.api.persistence.repositories

import org.bibletranslationtools.otter.common.domain.resourcecontainer.EditionFingerprint

/** The stored fingerprints of source editions, keyed by their resource metadata id. */
interface IEditionFingerprintRepository {
    suspend fun save(metadataId: Int, fingerprint: EditionFingerprint)

    /** Null until a fingerprint has been saved for [metadataId]. */
    suspend fun get(metadataId: Int): EditionFingerprint?

    /** Resource metadata ids of installed sources that have no fingerprint yet. */
    suspend fun sourcesWithoutFingerprint(): List<Int>
}
