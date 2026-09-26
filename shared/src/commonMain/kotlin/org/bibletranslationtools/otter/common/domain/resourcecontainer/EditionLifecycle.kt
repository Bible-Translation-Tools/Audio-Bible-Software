package org.bibletranslationtools.otter.common.domain.resourcecontainer

import org.bibletranslationtools.otter.common.api.persistence.repositories.IEditionUpgradeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceMetadataRepository
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.slf4j.LoggerFactory

/**
 * Removes source editions that are superseded and no longer used.
 *
 * An edition is superseded when an edition of the same source (same language, identifier and
 * creator) is strictly newer (see [EditionOrder.isNewer]); siblings with the same dates never
 * supersede each other, and the newest is never removed. An edition is used while any project was
 * derived from it, while a project chapter keeps its verse structure (a chapter held back on an
 * upgrade), or while a help (translation notes, questions) is linked to it: removing it would take
 * the help's content with it.
 *
 * Removal only happens at two moments: when a newer edition is installed, and when a project stops
 * using an edition. So an older edition imported on purpose, for example to downgrade a project to,
 * stays until one of those happens.
 */
class EditionLifecycle(
    private val installedEditions: InstalledSourceEditions,
    private val metadataRepository: IResourceMetadataRepository,
    private val deleteResourceContainer: DeleteResourceContainer,
    private val upgradeRepository: IEditionUpgradeRepository
) {
    private val logger = LoggerFactory.getLogger(EditionLifecycle::class.java)

    /** After [installed] was installed: removes the older editions it supersedes that nothing uses. */
    suspend fun retireSupersededBy(installed: ResourceMetadata): List<ResourceMetadata> =
        sameSource(installed)
            .filter { it.id != installed.id && EditionOrder.isNewer(installed, it) }
            .filter { remove(it) }

    /** After a project stopped using [edition]: removes it if it is superseded and nothing uses it. */
    suspend fun retireIfSuperseded(edition: ResourceMetadata): Boolean {
        val current = sameSource(edition).firstOrNull { it.id == edition.id } ?: return false
        val superseded = sameSource(edition).any { EditionOrder.isNewer(it, current) }
        return superseded && remove(current)
    }

    private suspend fun sameSource(edition: ResourceMetadata): List<ResourceMetadata> =
        installedEditions.editionsOf(edition.language.slug, edition.identifier)
            .filter { it.creator == edition.creator }

    private suspend fun isInUse(edition: ResourceMetadata): Boolean =
        metadataRepository.getAllDerivativesSuspend(edition).isNotEmpty() ||
            withContext(Dispatchers.IO) { upgradeRepository.chapterUsage(edition.id) } > 0 ||
            metadataRepository.getLinkedSuspend(edition).isNotEmpty()

    private suspend fun remove(edition: ResourceMetadata): Boolean {
        if (isInUse(edition)) return false
        val result = withContext(Dispatchers.IO) {
            runCatching { deleteResourceContainer.deleteSync(edition.path) }
                .onFailure { logger.error("Could not remove superseded edition ${edition.path}", it) }
                .getOrNull()
        }
        if (result == DeleteResult.SUCCESS) {
            logger.info("Removed superseded edition ${edition.language.slug}_${edition.identifier} v${edition.version} at ${edition.path}")
        }
        return result == DeleteResult.SUCCESS
    }
}
