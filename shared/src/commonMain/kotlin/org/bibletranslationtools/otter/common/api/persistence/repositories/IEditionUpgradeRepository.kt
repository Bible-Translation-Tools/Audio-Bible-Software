package org.bibletranslationtools.otter.common.api.persistence.repositories

import org.bibletranslationtools.otter.common.domain.collections.BookRebase
import org.bibletranslationtools.otter.common.domain.collections.ChapterReference
import org.bibletranslationtools.otter.common.domain.collections.ProjectBookState
import org.bibletranslationtools.otter.common.domain.collections.SourceBookText

/** Where a project's data lives and how it is moved between source editions. */
interface IEditionUpgradeRepository {
    fun projectBook(projectBookId: Int): ProjectBookState?

    /** The book [bookSlug] of source edition [editionId], with its chapters' text; null if it has none. */
    fun sourceBookText(editionId: Int, bookSlug: String): SourceBookText?

    /** Applies [rebase]: the project's folder, rows and derived containers, in one go. */
    fun apply(rebase: BookRebase)

    /** Chapter [chapterSort] of project book [projectBookId]'s editions, or null if there's no such chapter. */
    fun chapterReference(projectBookId: Int, chapterSort: Int): ChapterReference?

    /** How many project chapters still depend on [editionId] other than through a derived row. */
    fun chapterUsage(editionId: Int): Int
}
