package org.bibletranslationtools.bttrecorder2.legacymigration

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.api.persistence.repositories.IEditionUpgradeRepository

/**
 * The verses and chunks each installed [UlbTemplate] divides its chapters into: the units a project
 * created from it can record, and so the ranges a legacy take has to match exactly.
 *
 * @param chunks template to book slug to chapter number to its verse ranges, in order.
 */
class TemplateChunks(private val chunks: Map<UlbTemplate, Map<String, Map<Int, List<IntRange>>>>) {

    /** Whether [template] has [book] at all. */
    fun hasBook(template: UlbTemplate, book: String): Boolean = chunks[template]?.containsKey(book) == true

    /** [template]'s ranges for [chapter] of [book], or null when it has no such chapter. */
    fun chapter(template: UlbTemplate, book: String, chapter: Int): List<IntRange>? =
        chunks[template]?.get(book)?.get(chapter)
}

/**
 * Reads [TemplateChunks] from the installed template editions: the rows a project created from them
 * gets, rather than the template files, so what the planner matches against is what projects have.
 */
class LoadTemplateChunks(private val repository: IEditionUpgradeRepository) {

    /**
     * @param editions each template's installed edition id
     * @param books the book slugs needed; other books aren't read
     */
    suspend fun execute(editions: Map<UlbTemplate, Int>, books: Set<String>): TemplateChunks =
        withContext(Dispatchers.IO) {
            TemplateChunks(
                editions.mapValues { (_, editionId) ->
                    books.mapNotNull { book ->
                        repository.sourceBookText(editionId, book)?.let { text ->
                            book to text.chapters.mapValues { (_, chapter) ->
                                chapter.text.verses.map { it.range.start..it.range.end }
                            }
                        }
                    }.toMap()
                }
            )
        }
}
