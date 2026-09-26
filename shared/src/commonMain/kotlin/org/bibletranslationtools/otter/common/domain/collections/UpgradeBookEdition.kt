package org.bibletranslationtools.otter.common.domain.collections

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.api.persistence.repositories.IEditionUpgradeRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceMetadataRepository
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.domain.resourcecontainer.DescribeSourceEditions
import org.bibletranslationtools.otter.common.domain.resourcecontainer.EditionLifecycle
import org.bibletranslationtools.otter.common.domain.resourcecontainer.EditionOrder
import org.bibletranslationtools.otter.common.domain.resourcecontainer.InstalledSourceEditions
import org.bibletranslationtools.otter.common.domain.resourcecontainer.structure.ChapterDiff
import org.bibletranslationtools.otter.common.domain.resourcecontainer.structure.ChapterText
import org.bibletranslationtools.otter.common.domain.resourcecontainer.structure.EditionText
import org.bibletranslationtools.otter.common.domain.resourcecontainer.structure.StructuralDiff
import org.bibletranslationtools.otter.common.domain.resourcecontainer.structure.VerseChange
import org.bibletranslationtools.otter.common.domain.resourcecontainer.structure.VerseGroup
import org.bibletranslationtools.otter.common.domain.resourcecontainer.structure.VerseRange
import org.bibletranslationtools.otter.common.domain.resourcecontainer.structure.VerseText

/** What upgrading does to one chapter. */
enum class ChapterOutcome {
    /** Same verses and wording. */
    UNCHANGED,
    /** Same verses, new wording; recordings stay, and [ChapterUpgradePlan.textChangedVerses] may need a review. */
    TEXT_ONLY,
    /** New verse structure, taken on now: the chapter has no live recordings. */
    ADOPTS,
    /** New verse structure, not taken on: the chapter has recordings. It keeps its structure until its tasks are done. */
    HELD_BACK,
    /** A chapter the new edition has and the project doesn't; it is added. */
    ADDED
}

/**
 * @property diff how the chapter's structure edition compares with the new edition; null for an
 *   [ChapterOutcome.ADDED] chapter or one the new edition doesn't have.
 * @property chunksReset adopting removes the chapter's chunks (S8-Q7).
 */
data class ChapterUpgradePlan(
    val chapterSlug: String,
    val sort: Int,
    val outcome: ChapterOutcome,
    val diff: ChapterDiff?,
    val chunksReset: Boolean = false
) {
    val textChangedVerses: List<VerseRange> get() = diff?.textChangedVerses.orEmpty()

    /** The changes to the chapter's verse structure: merges, splits, renumbering and the like. */
    val structuralChanges: List<VerseGroup>
        get() = diff?.groups.orEmpty().filter { it.change != VerseChange.IDENTICAL && it.change != VerseChange.TEXT_CHANGED }
}

data class BookUpgradePlan(
    val projectBookId: Int,
    val bookSlug: String,
    val from: ResourceMetadata,
    val to: ResourceMetadata,
    val chapters: List<ChapterUpgradePlan>,
    internal val rebase: BookRebase
) {
    val heldBack: List<ChapterUpgradePlan> get() = chapters.filter { it.outcome == ChapterOutcome.HELD_BACK }

    /** The source book the project book refers to once the plan is applied. */
    val toSourceBookId: Int get() = rebase.toSourceBookId
}

/**
 * An installed edition a book can move to.
 *
 * @property newer it is newer than the book's current edition (an upgrade); otherwise a downgrade
 *   or a sibling edition.
 * @property distinguishingCode as in [org.bibletranslationtools.otter.common.domain.resourcecontainer.SourceEditionSummary].
 */
data class EditionChoice(val edition: ResourceMetadata, val newer: Boolean, val distinguishingCode: String?)

/**
 * A project book's source edition and where it can move.
 *
 * @property choices the other installed editions of its source, newest first.
 * @property heldBackChapters numbers of the chapters that keep an earlier edition's verse structure.
 */
data class BookEditionState(
    val projectBookId: Int,
    val current: ResourceMetadata,
    val currentCode: String?,
    val choices: List<EditionChoice>,
    val heldBackChapters: List<Int>
) {
    val updateAvailable: Boolean get() = choices.any { it.newer }
}

/** A book can't move to that edition. */
class UpgradeNotPossibleException(message: String) : IllegalStateException(message)

/**
 * Moves a project book to another installed edition of its source: an upgrade or a downgrade.
 *
 * The book's reference (its source) moves as a whole. Each chapter then:
 * - keeps its rows when only wording changed, recorded or not (S8-Q6);
 * - takes on the new verse structure when it has no live recordings, its deleted takes following
 *   their verses (S8-Q8) and its chunks reset (S8-Q7);
 * - keeps its old structure when it has recordings, and is reported as held back, with the old
 *   edition kept installed for it.
 *
 * Takes are never deleted. [plan] only reads; [apply] makes the change.
 */
class UpgradeBookEdition(
    private val repository: IEditionUpgradeRepository,
    private val metadataRepository: IResourceMetadataRepository,
    private val editionLifecycle: EditionLifecycle,
    private val installedEditions: InstalledSourceEditions,
    private val describeSourceEditions: DescribeSourceEditions
) {
    /** Where project book [projectBookId] stands, or null if there is no such book. */
    suspend fun editionState(projectBookId: Int): BookEditionState? {
        val book = withContext(Dispatchers.IO) { repository.projectBook(projectBookId) } ?: return null
        val current = book.sourceEdition
        val others = installedEditions.editionsOf(current.language.slug, current.identifier)
            .filter { it.creator == current.creator && it.id != current.id }
        val summaries = describeSourceEditions.describeAll(others + current)
        return BookEditionState(
            projectBookId = book.bookId,
            current = current,
            currentCode = summaries[current.id]?.distinguishingCode,
            choices = others.map { EditionChoice(it, EditionOrder.isNewer(it, current), summaries[it.id]?.distinguishingCode) },
            heldBackChapters = book.chapters.filter { it.structureEditionId != current.id }.map { it.sort }.sorted()
        )
    }

    suspend fun plan(projectBookId: Int, to: ResourceMetadata): BookUpgradePlan = withContext(Dispatchers.IO) {
        val book = repository.projectBook(projectBookId)
            ?: throw UpgradeNotPossibleException("No project book $projectBookId")
        val toText = repository.sourceBookText(to.id, book.slug)
            ?: throw UpgradeNotPossibleException("${to.identifier} v${to.version} has no ${book.slug}")

        // Each chapter is compared from the edition its own structure came from.
        val fromTexts = mutableMapOf<Int, SourceBookText?>()
        val fromChapters = book.chapters.associate { chapter ->
            val text = fromTexts.getOrPut(chapter.structureEditionId) {
                repository.sourceBookText(chapter.structureEditionId, book.slug)
            }?.chapters?.get(chapter.sort)?.text ?: chapter.numbersOnly(book.slug)
            chapter.slug to text
        }
        val diff = StructuralDiff.compare(
            EditionText(fromChapters),
            EditionText(toText.chapters.values.associate { it.text.slug to it.text })
        )

        val existing = book.chapters.map { it.sort }.toSet()
        val chapterPlans = book.chapters.map { chapter ->
            val newChapter = toText.chapters[chapter.sort]
            val chapterDiff = diff.chapters[chapter.slug]
            val outcome = when {
                newChapter == null -> ChapterOutcome.HELD_BACK
                chapterDiff == null || chapterDiff.isIdentical -> ChapterOutcome.UNCHANGED
                chapterDiff.isTextOnly -> ChapterOutcome.TEXT_ONLY
                chapter.hasLiveTakes -> ChapterOutcome.HELD_BACK
                else -> ChapterOutcome.ADOPTS
            }
            val plan = ChapterUpgradePlan(
                chapterSlug = chapter.slug,
                sort = chapter.sort,
                outcome = outcome,
                diff = chapterDiff,
                chunksReset = outcome == ChapterOutcome.ADOPTS && chapter.hasChunks
            )
            val rebase = ChapterRebase(
                chapterId = chapter.chapterId,
                toSourceChapterId = newChapter?.chapterId,
                structureEditionId = if (outcome == ChapterOutcome.HELD_BACK) chapter.structureEditionId else to.id,
                rewrite = if (outcome == ChapterOutcome.ADOPTS) ChapterRewrite(chapterDiff!!.groups, plan.chunksReset) else null
            )
            plan to rebase
        }
        val added = toText.chapters.filterKeys { it !in existing }.values.sortedBy { it.text.number }

        BookUpgradePlan(
            projectBookId = book.bookId,
            bookSlug = book.slug,
            from = book.sourceEdition,
            to = to,
            chapters = chapterPlans.map { it.first } +
                added.map { ChapterUpgradePlan(it.text.slug, it.text.number, ChapterOutcome.ADDED, null) },
            rebase = BookRebase(
                projectBookId = book.bookId,
                toEdition = to,
                toSourceBookId = toText.bookId,
                chapters = chapterPlans.map { it.second },
                newChapterSourceIds = added.map { it.chapterId }
            )
        )
    }

    /**
     * Carries out [plan], then removes the editions the book no longer needs, if they are
     * superseded and nothing else uses them (see [EditionLifecycle]).
     */
    suspend fun apply(plan: BookUpgradePlan) {
        withContext(Dispatchers.IO) { repository.apply(plan.rebase) }
        val stillNeeded = plan.rebase.chapters.map { it.structureEditionId }.toSet()
        metadataRepository.getAllSourcesSuspend()
            .filter { it.id == plan.from.id && it.id !in stillNeeded }
            .forEach { editionLifecycle.retireIfSuperseded(it) }
    }

    /** A chapter whose structure edition is no longer installed: its own verse numbers, no text. */
    private fun ProjectChapterState.numbersOnly(bookSlug: String) =
        ChapterText(slug, bookSlug, sort, verses.map { VerseText(it, null) })
}
