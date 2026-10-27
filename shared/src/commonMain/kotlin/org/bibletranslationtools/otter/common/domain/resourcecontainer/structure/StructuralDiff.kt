package org.bibletranslationtools.otter.common.domain.resourcecontainer.structure

/** A range of verse numbers: a single verse, or a bridge such as 40-41. */
data class VerseRange(val start: Int, val end: Int = start) {
    val verses: IntRange get() = start..end
    fun overlaps(other: VerseRange) = start <= other.end && other.start <= end
    override fun toString() = if (end > start) "$start-$end" else "$start"
}

/** One verse unit of a chapter: its range, and its text when known. */
data class VerseText(val range: VerseRange, val text: String?)

/** One chapter's verses, in order. [slug] is the chapter collection's slug, e.g. `act_19`. */
data class ChapterText(val slug: String, val book: String, val number: Int, val verses: List<VerseText>)

/** An edition's text, chapter by chapter, keyed by chapter slug. */
data class EditionText(val chapters: Map<String, ChapterText>)

/** How a group of verses changed from one edition to the other. */
enum class VerseChange {
    /** Same range, same text. */
    IDENTICAL,
    /** Same range, different wording. */
    TEXT_CHANGED,
    /** Several verses became one: a new bridge, or a verse folded into its neighbour. */
    MERGED,
    /** One verse became several: a bridge undone, or part of a verse given its own number. */
    SPLIT,
    /** Several verses regrouped into several others, such as 1-2, 3 becoming 1, 2-3. */
    REGROUPED,
    /** The same verse at a different number in the same chapter. */
    RENUMBERED,
    /** A verse the old edition doesn't have. */
    ADDED,
    /** A verse the new edition doesn't have. */
    REMOVED,
    /** A verse that left this chapter or this number; see [VerseGroup.movedTo]. */
    MOVED_OUT,
    /** A verse that arrived from another chapter or number; see [VerseGroup.movedFrom]. */
    MOVED_IN
}

/** Where a moved verse went, or came from. */
data class VerseLocation(val chapterSlug: String, val range: VerseRange)

/**
 * Verses of one edition ([from]) that correspond to verses of the other ([to]), within one
 * chapter. Either side is empty for an added, removed or moved verse.
 */
data class VerseGroup(
    val change: VerseChange,
    val from: List<VerseRange>,
    val to: List<VerseRange>,
    val movedTo: VerseLocation? = null,
    val movedFrom: VerseLocation? = null
)

/** How one chapter changed, as the verse groups that make it up. */
data class ChapterDiff(val chapterSlug: String, val groups: List<VerseGroup>) {
    /** The kinds of change in this chapter; empty when it is identical. */
    val changes: Set<VerseChange> get() = groups.map { it.change }.filter { it != VerseChange.IDENTICAL }.toSet()

    val isIdentical: Boolean get() = changes.isEmpty()

    /** Only wording changed: every verse keeps its number and range. */
    val isTextOnly: Boolean get() = changes == setOf(VerseChange.TEXT_CHANGED)

    /** Verses whose wording changed while keeping their range, in the new edition's numbering. */
    val textChangedVerses: List<VerseRange>
        get() = groups.filter { it.change == VerseChange.TEXT_CHANGED }.flatMap { it.to }
}

/** How one edition's text differs from another's, chapter by chapter. */
data class EditionDiff(val chapters: Map<String, ChapterDiff>) {
    /** Chapters whose verse structure changed, not just their wording. */
    val structurallyChanged: List<ChapterDiff>
        get() = chapters.values.filter { !it.isIdentical && !it.isTextOnly }
}

/**
 * Compares two editions' text chapter by chapter.
 *
 * Verses are paired by text first, so a shift in numbering can't pair the wrong ones:
 * 1. A verse at the same place with near-identical text is the same verse.
 * 2. A leftover verse whose text closely matches a leftover in the same or a neighbouring chapter
 *    was renumbered (same chapter) or moved (another chapter), such as the end of a chapter becoming
 *    the start of the next, or Malachi 4 becoming 3:19-24.
 * 3. What is left is paired by number: units sharing a verse number correspond. One to one is a
 *    text change, many to one a merge, one to many a split. A verse still left over is matched to
 *    its neighbour on the other side by text: folded into it (a merge, such as English ULB Acts
 *    19:41 folded into 19:40) or split out of it. Anything else was added or removed.
 *
 * Text is compared by character pairs of letters and digits, so wording can differ a little and
 * scripts without spaces between words (Thai) compare as well as those with them. A verse with no
 * text is only ever matched by number.
 */
object StructuralDiff {

    /** A leftover verse counts as folded into (or split out of) a neighbour at this containment. */
    private const val FOLD_CONTAINMENT = 0.7

    /** Two verses count as the same text at this similarity. */
    private const val MOVE_SIMILARITY = 0.8

    fun compare(from: EditionText, to: EditionText): EditionDiff {
        val slugs = (from.chapters.keys + to.chapters.keys).toSortedSet()
        val work = slugs.associateWith { slug ->
            ChapterWork(slug, from.chapters[slug]?.verses.orEmpty(), to.chapters[slug]?.verses.orEmpty())
        }
        work.values.forEach { it.anchorInPlace() }
        matchNearby(work.values, from, to)
        work.values.forEach { it.matchByNumber() }
        work.values.forEach { it.matchFolds() }
        return EditionDiff(work.mapValues { (slug, chapter) -> ChapterDiff(slug, chapter.groups()) })
    }

    /** The mutable state of one chapter while it is compared. */
    private class ChapterWork(val slug: String, fromVerses: List<VerseText>, toVerses: List<VerseText>) {
        /** Verses not yet paired, of each edition. */
        val fromVerses = fromVerses.toMutableList()
        val toVerses = toVerses.toMutableList()
        val matched = mutableListOf<Match>()
        val unmatchedFrom = mutableListOf<VerseText>()
        val unmatchedTo = mutableListOf<VerseText>()
        val moves = mutableListOf<VerseGroup>()

        class Match(val from: MutableList<VerseText>, val to: MutableList<VerseText>)

        /** Pass 1: the same range with near-identical text is the same verse. */
        fun anchorInPlace() {
            fromVerses.toList().forEach { verse ->
                val same = toVerses.firstOrNull { it.range == verse.range } ?: return@forEach
                if (similarity(verse.text, same.text) >= MOVE_SIMILARITY) {
                    matched.add(Match(mutableListOf(verse), mutableListOf(same)))
                    fromVerses.remove(verse)
                    toVerses.remove(same)
                }
            }
        }

        /**
         * Pass 3: groups verse units that share a verse number, across the two editions. Units are nodes
         * (old edition's first, then the new edition's) and overlapping ranges join their groups.
         */
        fun matchByNumber() {
            val parent = IntArray(fromVerses.size + toVerses.size) { it }
            fun root(i: Int): Int = if (parent[i] == i) i else root(parent[i])
            fun join(a: Int, b: Int) { parent[root(a)] = root(b) }
            fun toNode(j: Int) = fromVerses.size + j

            fromVerses.forEachIndexed { i, a ->
                toVerses.forEachIndexed { j, b ->
                    if (a.range.overlaps(b.range)) join(i, toNode(j))
                }
            }
            val groups = linkedMapOf<Int, Match>()
            fromVerses.forEachIndexed { i, verse ->
                val hasCounterpart = toVerses.indices.any { root(toNode(it)) == root(i) }
                if (hasCounterpart) groups.getOrPut(root(i)) { Match(mutableListOf(), mutableListOf()) }.from.add(verse)
                else unmatchedFrom.add(verse)
            }
            toVerses.forEachIndexed { j, verse ->
                val group = groups[root(toNode(j))]
                if (group != null) group.to.add(verse) else unmatchedTo.add(verse)
            }
            matched.addAll(groups.values)
        }

        /** A leftover verse whose text sits inside its neighbour on the other side. */
        fun matchFolds() {
            unmatchedFrom.toList().forEach { verse ->
                val neighbour = neighbouringMatch(verse.range) { it.to }
                if (neighbour != null && containment(verse.text, neighbour.to.joinText()) >= FOLD_CONTAINMENT) {
                    neighbour.from.add(verse)
                    unmatchedFrom.remove(verse)
                }
            }
            unmatchedTo.toList().forEach { verse ->
                val neighbour = neighbouringMatch(verse.range) { it.from }
                if (neighbour != null && containment(verse.text, neighbour.from.joinText()) >= FOLD_CONTAINMENT) {
                    neighbour.to.add(verse)
                    unmatchedTo.remove(verse)
                }
            }
        }

        /** The match on the other side holding the verse just before or after [range]. */
        private fun neighbouringMatch(range: VerseRange, side: (Match) -> List<VerseText>): Match? =
            matched.firstOrNull { match -> side(match).any { it.range.end == range.start - 1 } }
                ?: matched.firstOrNull { match -> side(match).any { it.range.start == range.end + 1 } }

        fun groups(): List<VerseGroup> {
            val groups = matched.map { match -> match.toGroup() } +
                moves +
                unmatchedFrom.map { VerseGroup(VerseChange.REMOVED, listOf(it.range), emptyList()) } +
                unmatchedTo.map { VerseGroup(VerseChange.ADDED, emptyList(), listOf(it.range)) }
            return groups.sortedWith(compareBy({ (it.to.firstOrNull() ?: it.from.first()).start }, { it.from.isEmpty() }))
        }

        private fun Match.toGroup(): VerseGroup {
            val fromRanges = from.sortedBy { it.range.start }.map { it.range }
            val toRanges = to.sortedBy { it.range.start }.map { it.range }
            val change = when {
                from.size == 1 && to.size == 1 && fromRanges == toRanges ->
                    if (textDiffers(from.single().text, to.single().text)) VerseChange.TEXT_CHANGED else VerseChange.IDENTICAL
                to.size == 1 -> VerseChange.MERGED
                from.size == 1 -> VerseChange.SPLIT
                else -> VerseChange.REGROUPED
            }
            return VerseGroup(change, fromRanges, toRanges)
        }
    }

    /**
     * Pass 2: pairs leftovers by text within the same chapter or a neighbouring one of the same
     * book, best match first: renumbered in its chapter, or moved to another. Only neighbours are
     * compared, which is where versifications move verses, and which keeps a whole renumbered book
     * fast to compare.
     */
    private fun matchNearby(chapters: Collection<ChapterWork>, from: EditionText, to: EditionText) {
        fun info(slug: String) = from.chapters[slug] ?: to.chapters[slug]
        chapters.groupBy { info(it.slug)?.book ?: it.slug.substringBeforeLast('_') }.values.forEach { book ->
            val byNumber = book.associateBy { info(it.slug)?.number ?: 0 }
            val candidates = book.flatMap { outOf ->
                val number = info(outOf.slug)?.number ?: 0
                val nearby = listOfNotNull(byNumber[number - 1], outOf, byNumber[number + 1])
                outOf.fromVerses.flatMap { verse ->
                    nearby.flatMap { into ->
                        into.toVerses.mapNotNull { arrived ->
                            // In its own chapter, a verse overlapping its old range was regrouped,
                            // merged or split, not renumbered: pass 3 decides which.
                            if (into === outOf && verse.range.overlaps(arrived.range)) return@mapNotNull null
                            val score = similarity(verse.text, arrived.text)
                            if (score >= MOVE_SIMILARITY) Candidate(outOf, verse, into, arrived, score) else null
                        }
                    }
                }
            }.sortedWith(
                compareByDescending<Candidate> { it.score }
                    .thenBy { if (it.outOf === it.into) 0 else 1 }
                    .thenBy { kotlin.math.abs(it.verse.range.start - it.arrived.range.start) }
            )
            candidates.forEach { c ->
                if (c.verse !in c.outOf.fromVerses || c.arrived !in c.into.toVerses) return@forEach
                c.outOf.fromVerses.remove(c.verse)
                c.into.toVerses.remove(c.arrived)
                if (c.outOf === c.into) {
                    c.outOf.moves.add(VerseGroup(VerseChange.RENUMBERED, listOf(c.verse.range), listOf(c.arrived.range)))
                } else {
                    c.outOf.moves.add(
                        VerseGroup(VerseChange.MOVED_OUT, listOf(c.verse.range), emptyList(), movedTo = VerseLocation(c.into.slug, c.arrived.range))
                    )
                    c.into.moves.add(
                        VerseGroup(VerseChange.MOVED_IN, emptyList(), listOf(c.arrived.range), movedFrom = VerseLocation(c.outOf.slug, c.verse.range))
                    )
                }
            }
        }
    }

    private class Candidate(
        val outOf: ChapterWork,
        val verse: VerseText,
        val into: ChapterWork,
        val arrived: VerseText,
        val score: Double
    )

    private fun List<VerseText>.joinText(): String? =
        mapNotNull { it.text }.takeIf { it.isNotEmpty() }?.joinToString(" ")

    private fun textDiffers(a: String?, b: String?): Boolean =
        a != null && b != null && normalized(a) != normalized(b)

    private val notLetterOrDigit = Regex("[^\\p{L}\\p{N}]+")

    private fun normalized(text: String) = text.lowercase().replace(notLetterOrDigit, " ").trim()

    private fun bigrams(text: String?): Set<String> {
        val letters = text?.lowercase()?.replace(notLetterOrDigit, "") ?: return emptySet()
        return if (letters.length < 2) setOfNotNull(letters.ifEmpty { null }) else letters.windowed(2).toSet()
    }

    /** How much of [part] appears in [whole]: 1.0 when all of its character pairs do. */
    private fun containment(part: String?, whole: String?): Double {
        val p = bigrams(part)
        if (p.isEmpty()) return 0.0
        return p.intersect(bigrams(whole)).size.toDouble() / p.size
    }

    private fun similarity(a: String?, b: String?): Double {
        val x = bigrams(a)
        val y = bigrams(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return x.intersect(y).size.toDouble() / (x union y).size
    }
}
