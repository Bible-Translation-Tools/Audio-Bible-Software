package org.bibletranslationtools.otter.common.domain.resourcecontainer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.api.persistence.repositories.IEditionUpgradeRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceMetadataRepository
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.slf4j.LoggerFactory

/**
 * Removing a source edition from the device.
 *
 * Editions are never removed automatically (O1-Q5): not when a newer one is installed, not when a
 * project moves to another edition, not when the last project using one is deleted. This is the
 * check a remove action a user starts will need: an edition can only go while nothing uses it. It is
 * used while any project was derived from it, while a project chapter keeps its verse structure (a
 * chapter held back on an upgrade), or while a help (translation notes, questions) is linked to it,
 * since removing it would take the help's content with it.
 */
class EditionLifecycle(
    private val metadataRepository: IResourceMetadataRepository,
    private val deleteResourceContainer: DeleteResourceContainer,
    private val upgradeRepository: IEditionUpgradeRepository
) {
    private val logger = LoggerFactory.getLogger(EditionLifecycle::class.java)

    suspend fun isInUse(edition: ResourceMetadata): Boolean =
        metadataRepository.getAllDerivativesSuspend(edition).isNotEmpty() ||
            withContext(Dispatchers.IO) { upgradeRepository.chapterUsage(edition.id) } > 0 ||
            metadataRepository.getLinkedSuspend(edition).isNotEmpty()

    /** Removes [edition] and its files if nothing uses it; true when it was removed. */
    suspend fun removeIfUnused(edition: ResourceMetadata): Boolean {
        if (isInUse(edition)) return false
        val result = withContext(Dispatchers.IO) {
            runCatching { deleteResourceContainer.deleteSync(edition.path) }
                .onFailure { logger.error("Could not remove edition ${edition.path}", it) }
                .getOrNull()
        }
        if (result == DeleteResult.SUCCESS) {
            logger.info("Removed edition ${edition.language.slug}_${edition.identifier} v${edition.version} at ${edition.path}")
        }
        return result == DeleteResult.SUCCESS
    }
}
