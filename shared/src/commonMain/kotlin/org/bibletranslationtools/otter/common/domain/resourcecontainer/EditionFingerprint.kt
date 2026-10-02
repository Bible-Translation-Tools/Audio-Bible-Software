package org.bibletranslationtools.otter.common.domain.resourcecontainer

import org.bibletranslationtools.otter.common.collections.OtterTree
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.CollectionOrContent
import org.bibletranslationtools.otter.common.data.primitives.Content
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import java.security.MessageDigest

/**
 * One chapter of a source edition, reduced to two hashes.
 *
 * @property structureHash the chapter's verse ranges in order, for example `1;2;3-4;5`. Equal
 *   hashes mean the same verse structure.
 * @property textHash the chapter's verse text in order, footnotes excluded (they never reach
 *   [Content.text]) and whitespace collapsed. Equal hashes mean the same wording.
 */
data class ChapterFingerprint(
    val chapterSlug: String,
    val structureHash: String,
    val textHash: String
)

/**
 * What a source edition contains, independent of the version label it carries.
 *
 * Labels can't be trusted to tell editions apart: two English ULB releases with different verse
 * structures both say `version: '12'`. Two editions with the same fingerprints have the same verses
 * and the same wording.
 *
 * @property detectedVersification the standard versification the text was found to follow, or
 *   null when none was detected (see VersificationDetector).
 * @property structureFingerprint all chapters' [ChapterFingerprint.structureHash]es combined.
 * @property textFingerprint all chapters' [ChapterFingerprint.textHash]es combined.
 */
data class EditionFingerprint(
    val detectedVersification: String?,
    val structureFingerprint: String,
    val textFingerprint: String,
    val chapters: List<ChapterFingerprint>
) {
    /**
     * Six hex characters derived from both fingerprints: short enough for a folder name, and
     * enough to keep apart editions that share a version label.
     */
    val shortCode: String get() = sha256("$structureFingerprint\n$textFingerprint").take(6)

    companion object {
        /** The fingerprint of the text in [tree], a parsed source before any gap-filling. */
        fun of(tree: OtterTree<CollectionOrContent>, detectedVersification: String?): EditionFingerprint {
            val chapters = chapterVerses(tree)
                .map { (slug, verses) ->
                    ChapterFingerprint(
                        chapterSlug = slug,
                        structureHash = sha256(verses.joinToString(";") { it.range() }),
                        textHash = sha256(verses.joinToString("\n") { it.text.orEmpty().normalized() })
                    )
                }
                .sortedBy { it.chapterSlug }
            return EditionFingerprint(
                detectedVersification = detectedVersification,
                structureFingerprint = sha256(chapters.joinToString("\n") { "${it.chapterSlug}\t${it.structureHash}" }),
                textFingerprint = sha256(chapters.joinToString("\n") { "${it.chapterSlug}\t${it.textHash}" }),
                chapters = chapters
            )
        }
    }
}

/**
 * Who a source is and what it contains. Two sources are the same edition when all of these match;
 * the version label is deliberately not part of it.
 */
data class EditionIdentityKey(
    val language: String,
    val identifier: String,
    val creator: String,
    val structureFingerprint: String,
    val textFingerprint: String
)

/**
 * Whether [a] and [b] are the same edition: same language, identifier and creator, and identical
 * structure and text. A difference in version label alone doesn't make a new edition.
 */
fun isSameEdition(a: EditionIdentityKey, b: EditionIdentityKey): Boolean = a == b

/** Chapter slug to its TEXT content, ordered by verse range. Chapters without verses are skipped. */
private fun chapterVerses(tree: OtterTree<CollectionOrContent>): Map<String, List<Content>> {
    val chapters = mutableMapOf<String, List<Content>>()
    fun walk(node: OtterTree<CollectionOrContent>) {
        val value = node.value
        val verses = node.children
            .mapNotNull { it.value as? Content }
            .filter { it.type == ContentType.TEXT }
        if (value is Collection && verses.isNotEmpty()) {
            chapters[value.slug] = verses.sortedWith(compareBy({ it.start }, { it.end }))
        }
        node.children.filterIsInstance<OtterTree<CollectionOrContent>>().forEach(::walk)
    }
    walk(tree)
    return chapters
}

private fun Content.range() = if (end > start) "$start-$end" else "$start"

private val whitespace = Regex("\\s+")

private fun String.normalized() = trim().replace(whitespace, " ")

private fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
