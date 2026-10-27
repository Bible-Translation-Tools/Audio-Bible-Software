package org.bibletranslationtools.otter.common.domain.versification

/**
 * The verse structure a source's own text declares: for each book slug, the last verse number of
 * each chapter it contains.
 */
data class TextStructure(val lastVerseByChapter: Map<String, Map<Int, Int>>)

/**
 * The versification that best fits a text.
 *
 * @property differingChapters chapters where the text and [versification] disagree, as
 *   `"<book> <chapter>"`, for example `"act 19"`. Empty means an exact fit.
 */
data class VersificationMatch(
    val code: String,
    val versification: Versification,
    val differingChapters: List<String>
)

/**
 * Picks the candidate versification whose chapter and verse counts best fit a text.
 *
 * The score is the number of chapters that disagree, counting only books the text contains: a
 * chapter whose last verse differs, a chapter the text has and the candidate doesn't, or a chapter
 * the candidate has and the text doesn't. The lowest score wins; ties go to the earlier candidate.
 */
object VersificationDetector {

    fun detect(text: TextStructure, candidates: List<Pair<String, Versification>>): VersificationMatch? {
        return candidates
            .map { (code, versification) ->
                VersificationMatch(code, versification, differingChapters(text, versification))
            }
            .minByOrNull { it.differingChapters.size }
    }

    private fun differingChapters(text: TextStructure, versification: Versification): List<String> {
        val differing = mutableListOf<String>()
        for ((book, chapters) in text.lastVerseByChapter) {
            val chapterCount = versification.getChaptersInBook(book)
            val allChapters = (chapters.keys + (1..chapterCount)).toSortedSet()
            for (chapter in allChapters) {
                val expected = if (chapter <= chapterCount) versification.getVersesInChapter(book, chapter) else 0
                if (chapters[chapter] != expected) differing.add("$book $chapter")
            }
        }
        return differing
    }
}
