package org.bibletranslationtools.otter.common.domain.collections

import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.domain.resourcecontainer.DescribeSourceEditions
import org.bibletranslationtools.otter.common.domain.resourcecontainer.EditionOrder
import org.bibletranslationtools.otter.common.domain.resourcecontainer.InstalledSourceEditions

/**
 * Moving the books of a project to [to] (O1-Q1).
 *
 * @property books one plan per book that moves.
 * @property unavailable slugs of the books [to] doesn't have: they stay where they are.
 */
data class ProjectUpgradePlan(
    val to: ResourceMetadata,
    val books: List<BookUpgradePlan>,
    val unavailable: List<String>
) {
    /** Chapters that keep their current verse structure because they're recorded, by book slug. */
    val heldBack: Map<String, List<Int>>
        get() = books.associate { book -> book.bookSlug to book.heldBack.map { it.sort } }.filterValues { it.isNotEmpty() }
}

/**
 * Moves every book of a project to another installed edition of its source: Orature's projects are
 * whole Bibles, and each book moves as [UpgradeBookEdition] moves one, chapter by chapter.
 */
class UpgradeProjectEdition(
    private val upgradeBookEdition: UpgradeBookEdition,
    private val installedEditions: InstalledSourceEditions,
    private val describeSourceEditions: DescribeSourceEditions
) {
    /**
     * The installed editions a project whose books are on [current] can move to, newest first:
     * every edition of their source except one all of them are already on, each compared with the
     * oldest edition any book is on.
     */
    suspend fun choices(current: Collection<ResourceMetadata>): List<EditionChoice> {
        val first = current.firstOrNull() ?: return emptyList()
        val currentIds = current.map { it.id }.toSet()
        val oldest = current.sortedWith(EditionOrder.newestFirst).last()
        val editions = installedEditions.editionsOf(first.language.slug, first.identifier)
            .filter { it.creator == first.creator }
            .filterNot { currentIds.size == 1 && it.id in currentIds }
        val summaries = describeSourceEditions.describeAll(editions)
        return editions.map { EditionChoice(it, EditionRelation.of(it, oldest), summaries[it.id]?.distinguishingCode) }
    }

    /**
     * Plans moving books [projectBookIds] (project book collections) to [to], reporting (books
     * checked, books in all) as it goes. Only reads.
     */
    suspend fun plan(
        projectBookIds: List<Int>,
        to: ResourceMetadata,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): ProjectUpgradePlan {
        val books = mutableListOf<BookUpgradePlan>()
        val unavailable = mutableListOf<String>()
        projectBookIds.forEachIndexed { index, id ->
            onProgress(index, projectBookIds.size)
            try {
                val plan = upgradeBookEdition.plan(id, to)
                if (plan.from.id != to.id) books += plan
            } catch (e: UpgradeNotPossibleException) {
                e.bookSlug?.let { unavailable += it } ?: throw e
            }
        }
        return ProjectUpgradePlan(to, books, unavailable)
    }

    /** Carries out [plan] book by book, reporting (books done, books in all) after each. */
    suspend fun apply(plan: ProjectUpgradePlan, onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        plan.books.forEachIndexed { index, book ->
            upgradeBookEdition.apply(book)
            onProgress(index + 1, plan.books.size)
        }
    }
}
