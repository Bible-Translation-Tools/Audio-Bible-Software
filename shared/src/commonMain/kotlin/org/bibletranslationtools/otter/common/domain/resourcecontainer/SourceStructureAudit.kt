package org.bibletranslationtools.otter.common.domain.resourcecontainer

import kotlinx.serialization.Serializable
import org.bibletranslationtools.otter.common.collections.OtterTree
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.CollectionOrContent
import org.bibletranslationtools.otter.common.data.primitives.Content
import org.bibletranslationtools.otter.common.data.primitives.ContentType

/** One stored verse row of a source chapter. */
data class StoredVerseRow(val start: Int, val end: Int)

/**
 * How one installed source's stored verse rows compare with its own text.
 *
 * Verses are written `"<chapter slug>:<verse>"`, for example `"act_19:41"`.
 *
 * @property droppedVerses verses the text contains that no stored row covers: text the import lost.
 * @property emptyAfterTextEnd rows for verses after the text's last verse in a chapter. The import
 *   added these from a versification, so they carry no text (English Acts 19:41).
 * @property emptyInsideChapter rows for verses missing inside a chapter the text does contain:
 *   omitted variants such as MRK 7:16, kept so they stay recordable. Expected.
 * @property templateChapters chapters stored with no text at all: templates for books or chapters
 *   the text doesn't contain. Expected.
 */
@Serializable
data class SourceStructureFindings(
    val identifier: String,
    val language: String,
    val version: String,
    val path: String,
    val droppedVerses: List<String>,
    val emptyAfterTextEnd: List<String>,
    val emptyInsideChapter: List<String>,
    val templateChapters: Int
) {
    val needsRepair: Boolean get() = droppedVerses.isNotEmpty() || emptyAfterTextEnd.isNotEmpty()
}

/**
 * Compares a source's text, as chapter slug to the verses it covers, with its stored rows, as
 * chapter slug to verse rows.
 */
fun auditSourceStructure(
    identifier: String,
    language: String,
    version: String,
    path: String,
    textVerses: Map<String, Set<Int>>,
    storedRows: Map<String, List<StoredVerseRow>>
): SourceStructureFindings {
    val dropped = mutableListOf<String>()
    val afterEnd = mutableListOf<String>()
    val inside = mutableListOf<String>()
    var templateChapters = 0

    for (chapter in (textVerses.keys + storedRows.keys).sortedWith(chapterOrder)) {
        val text = textVerses[chapter].orEmpty()
        val stored = storedRows[chapter].orEmpty().flatMap { it.start..it.end }.toSet()
        if (text.isEmpty()) {
            if (stored.isNotEmpty()) templateChapters++
            continue
        }
        val lastTextVerse = text.max()
        (text - stored).sorted().forEach { dropped.add("$chapter:$it") }
        (stored - text).sorted().forEach { verse ->
            (if (verse > lastTextVerse) afterEnd else inside).add("$chapter:$verse")
        }
    }
    return SourceStructureFindings(
        identifier, language, version, path, dropped, afterEnd, inside, templateChapters
    )
}

/** Chapter slug to the verse numbers its TEXT content covers, bridges expanded. */
fun textVersesByChapter(tree: OtterTree<CollectionOrContent>): Map<String, Set<Int>> {
    val chapters = mutableMapOf<String, Set<Int>>()
    fun walk(node: OtterTree<CollectionOrContent>) {
        val contents = node.children.mapNotNull { it.value as? Content }
        val value = node.value
        if (value is Collection && contents.isNotEmpty()) {
            chapters[value.slug] = contents
                .filter { it.type == ContentType.TEXT }
                .flatMap { it.start..it.end }
                .toSet()
        }
        node.children.filterIsInstance<OtterTree<CollectionOrContent>>().forEach(::walk)
    }
    walk(tree)
    return chapters
}

/** `gen_2` before `gen_10`: by book slug, then by chapter number. */
private val chapterOrder = compareBy<String>(
    { it.substringBeforeLast('_') },
    { it.substringAfterLast('_').toIntOrNull() ?: 0 }
)
