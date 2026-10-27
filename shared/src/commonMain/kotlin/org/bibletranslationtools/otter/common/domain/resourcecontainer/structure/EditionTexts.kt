package org.bibletranslationtools.otter.common.domain.resourcecontainer.structure

import org.bibletranslationtools.otter.common.collections.OtterTree
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.CollectionOrContent
import org.bibletranslationtools.otter.common.data.primitives.Content
import org.bibletranslationtools.otter.common.data.primitives.ContentType

/** The verse text of [tree], a parsed source: every chapter that has verses, by chapter slug. */
fun editionTextOf(tree: OtterTree<CollectionOrContent>): EditionText {
    val chapters = mutableMapOf<String, ChapterText>()
    fun walk(node: OtterTree<CollectionOrContent>, book: String?) {
        val value = node.value as? Collection
        val verses = node.children
            .mapNotNull { it.value as? Content }
            .filter { it.type == ContentType.TEXT }
        if (value != null && verses.isNotEmpty()) {
            chapters[value.slug] = ChapterText(
                slug = value.slug,
                book = book ?: value.slug.substringBeforeLast('_'),
                number = value.sort,
                verses = verses
                    .sortedWith(compareBy({ it.start }, { it.end }))
                    .map { VerseText(VerseRange(it.start, maxOf(it.start, it.end)), it.text) }
            )
        }
        node.children.filterIsInstance<OtterTree<CollectionOrContent>>().forEach { child ->
            walk(child, if (verses.isEmpty()) value?.slug else book)
        }
    }
    walk(tree, null)
    return EditionText(chapters)
}
