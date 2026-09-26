package org.bibletranslationtools.otter.common.domain.resourcecontainer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.api.persistence.repositories.IEditionUpgradeRepository
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.domain.resourcecontainer.structure.VerseRange

/**
 * How well an installed edition's verse units fit a set of recordings.
 *
 * @property unmatched recorded units the edition has no identical unit for, as
 *   `<chapter>:<range>`: a take there would have to be split, joined or dropped.
 */
data class EditionFit(val edition: ResourceMetadata, val unmatched: List<String>) {
    val exact: Boolean get() = unmatched.isEmpty()
}

/**
 * Chooses the source edition for recordings made outside this app, such as a legacy BTT Recorder
 * project: the installed edition whose verse units best match the units that were recorded
 * (S13-Q3). A chunk-mode recording of 1-3 fits an edition with a 1-3 unit, and verse-by-verse
 * recordings fit an edition with those verses.
 */
class MatchEditionToRecordings(
    private val installedEditions: InstalledSourceEditions,
    private val repository: IEditionUpgradeRepository
) {
    /**
     * The installed editions of [identifier] in [languageSlug] that have [bookSlug], best fit first:
     * fewest unmatched units, then newest.
     *
     * @param recorded each chapter's recorded units, by chapter number.
     */
    suspend fun rank(
        languageSlug: String,
        identifier: String,
        bookSlug: String,
        recorded: Map<Int, List<VerseRange>>
    ): List<EditionFit> {
        val editions = installedEditions.editionsOf(languageSlug, identifier) // newest first
        return withContext(Dispatchers.IO) {
            editions.mapNotNull { edition ->
                val book = repository.sourceBookText(edition.id, bookSlug) ?: return@mapNotNull null
                val unmatched = recorded.toSortedMap().flatMap { (chapter, units) ->
                    val available = book.chapters[chapter]?.text?.verses?.map { it.range }?.toSet().orEmpty()
                    units.distinct().sortedBy { it.start }.filter { it !in available }.map { "$chapter:$it" }
                }
                EditionFit(edition, unmatched)
            }
        }.sortedBy { it.unmatched.size } // stable, so the newest wins a tie
    }
}
