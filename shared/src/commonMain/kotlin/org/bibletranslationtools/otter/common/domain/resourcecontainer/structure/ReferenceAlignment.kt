package org.bibletranslationtools.otter.common.domain.resourcecontainer.structure

import org.bibletranslationtools.otter.common.api.persistence.repositories.IEditionUpgradeRepository
import java.util.concurrent.ConcurrentHashMap

/**
 * Lines a project chapter's verses up with its reference (the book's source edition) when the two
 * have different structures: a chapter held back on an upgrade keeps the verses it was recorded
 * with, while its book refers to a newer edition.
 *
 * A merged verse answers for every project verse it absorbed: with English ULB Acts 19:41 folded
 * into 19:40, project verses 40 and 41 both play the reference's 40.
 */
class ReferenceAlignment(private val repository: IEditionUpgradeRepository) {

    private val alignments = ConcurrentHashMap<AlignmentKey, Map<VerseRange, Int>>()

    private data class AlignmentKey(val bookId: Int, val chapter: Int, val structureEdition: Int, val referenceEdition: Int)

    /**
     * The reference verse number to look up for project verse [verse] of chapter [chapterSort] of
     * project book [projectBookId]: its own start when the chapter follows its reference, or null
     * when the reference has no counterpart for it in this chapter.
     */
    fun referenceStart(projectBookId: Int, chapterSort: Int, verse: VerseRange): Int? {
        val reference = repository.chapterReference(projectBookId, chapterSort) ?: return verse.start
        if (reference.structureEditionId == reference.referenceEditionId) return verse.start
        val key = AlignmentKey(projectBookId, chapterSort, reference.structureEditionId, reference.referenceEditionId)
        val alignment = alignments.getOrPut(key) {
            val from = repository.sourceBookText(reference.structureEditionId, reference.bookSlug)?.chapters?.get(chapterSort)?.text
            val to = repository.sourceBookText(reference.referenceEditionId, reference.bookSlug)?.chapters?.get(chapterSort)?.text
            if (from == null || to == null) emptyMap() else align(from, to)
        }
        return alignment[verse]
    }

    private fun align(from: ChapterText, to: ChapterText): Map<VerseRange, Int> {
        val diff = StructuralDiff.compare(
            EditionText(mapOf(from.slug to from)),
            EditionText(mapOf(from.slug to to.copy(slug = from.slug)))
        ).chapters.getValue(from.slug)
        return diff.groups
            .filter { it.to.isNotEmpty() && it.from.isNotEmpty() }
            .flatMap { group -> group.from.map { it to group.to.first().start } }
            .toMap()
    }
}
