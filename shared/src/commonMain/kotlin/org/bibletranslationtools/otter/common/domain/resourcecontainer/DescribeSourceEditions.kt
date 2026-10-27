package org.bibletranslationtools.otter.common.domain.resourcecontainer

import org.bibletranslationtools.otter.common.api.persistence.repositories.IEditionFingerprintRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceMetadataRepository
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata

/**
 * How to show a source edition to a user.
 *
 * @property distinguishingCode the edition's short content code, set only when another installed
 *   edition of the same source has the same version label and issued date, so the two would
 *   otherwise look identical (like the two English ULB zips that both say `12`, issued 2017-11-29).
 * @property newerEdition the newest installed edition of the same source, when it is newer than
 *   this one (see [EditionOrder]); a project on this edition has an update available.
 */
data class SourceEditionSummary(
    val edition: ResourceMetadata,
    val distinguishingCode: String?,
    val newerEdition: ResourceMetadata?
) {
    val updateAvailable: Boolean get() = newerEdition != null
}

/** Describes the source editions projects use, against the editions installed on this device. */
class DescribeSourceEditions(
    private val metadataRepository: IResourceMetadataRepository,
    private val fingerprintRepository: IEditionFingerprintRepository
) {
    suspend fun describe(edition: ResourceMetadata): SourceEditionSummary =
        describeAll(listOf(edition)).getValue(edition.id)

    /** Summaries of [editions] by id, reading the installed editions once for all of them. */
    suspend fun describeAll(editions: Collection<ResourceMetadata>): Map<Int, SourceEditionSummary> {
        val installed = metadataRepository.getAllSourcesSuspend()
        return editions.distinctBy { it.id }.associate { edition ->
            val sameSource = installed.filter {
                it.language.slug == edition.language.slug &&
                    it.identifier == edition.identifier &&
                    it.creator == edition.creator
            }
            val lookAlike = sameSource.any {
                it.id != edition.id && it.version == edition.version && it.issued == edition.issued
            }
            val newest = EditionOrder.newest(sameSource)
            edition.id to SourceEditionSummary(
                edition = edition,
                distinguishingCode = if (lookAlike) fingerprintRepository.get(edition.id)?.shortCode else null,
                newerEdition = newest?.takeIf { EditionOrder.isNewer(it, edition) }
            )
        }
    }
}
