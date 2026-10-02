/**
 * Copyright (C) 2020-2024 Wycliffe Associates
 *
 * This file is part of Orature.
 *
 * Orature is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Orature is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Orature.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.bibletranslationtools.otter.common.domain.resourcecontainer.project

import org.bibletranslationtools.otter.common.collections.OtterTree
import org.bibletranslationtools.otter.common.collections.OtterTreeNode
import org.bibletranslationtools.otter.common.data.primitives.*
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.domain.resourcecontainer.toCollection
import org.bibletranslationtools.otter.common.domain.versification.Versification
import org.bibletranslationtools.otter.common.api.persistence.repositories.IVersificationRepository
import org.wycliffeassociates.resourcecontainer.ResourceContainer
import org.wycliffeassociates.resourcecontainer.entity.Project

private const val FORMAT = "text/usfm"
private const val DEFAULT_VERSIFICATION = "ulb"

/**
 * Builds empty book, chapter and verse trees from a [Versification]: the structure a translator can
 * record into when the source text supplies none.
 */
class VersificationTreeBuilder(
    private val versificationRepository: IVersificationRepository
) {
    /** One tree per book [the container's declared versification][getVersification] lists. */
    fun build(container: ResourceContainer): List<OtterTree<CollectionOrContent>>? {
        val versification = getVersification(container) ?: return null
        val versificationSlug = container.manifest.projects.firstOrNull()?.versification ?: DEFAULT_VERSIFICATION
        return versification.getBookSlugs().map { book ->
            bookTree(versification, container, book, versificationSlug)
        }
    }

    private fun getVersification(container: ResourceContainer): Versification? {
        val versificationCode = container.manifest.projects.firstOrNull()?.versification ?: return null
        if (versificationCode == "") return null
        return versificationRepository.getVersification(versificationCode).blockingGet()
    }

    /**
     * An empty tree for [book]: every chapter and verse [versification] declares. Titled from the
     * container's manifest entry for the book when it has one.
     */
    fun bookTree(
        versification: Versification,
        container: ResourceContainer,
        book: String,
        versificationSlug: String
    ): OtterTree<CollectionOrContent> {
        val project = container.manifest.projects
            .firstOrNull { it.identifier == book }
            ?: Project(
                title = "",
                versification = versificationSlug,
                identifier = book,
                sort = Int.MAX_VALUE,
                path = "",
                categories = listOf(),
            )
        val projectTree = OtterTree<CollectionOrContent>(project.toCollection())
        for (chapter in 1..versification.getChaptersInBook(project.identifier)) {
            projectTree.addChild(chapterTree(versification, project.identifier, chapter))
        }
        return projectTree
    }

    /** An empty tree for one chapter: its title, its whole-chapter chunk, and one row per verse. */
    fun chapterTree(
        versification: Versification,
        book: String,
        chapter: Int
    ): OtterTree<CollectionOrContent> {
        val chapterCollection = Collection(
            sort = chapter,
            slug = "${book}_${chapter}",
            labelKey = ChapterLabel.of(book),
            titleKey = "$chapter",
            resourceContainer = null
        )
        val chapterTree = OtterTree<CollectionOrContent>(chapterCollection)
        val verses = versification.getVersesInChapter(book, chapter)

        val chapChunk = Content(
            sort = 0,
            labelKey = ContentLabel.CHAPTER.value,
            start = 1,
            end = verses,
            selectedTake = null,
            text = null,
            format = FORMAT,
            type = ContentType.META,
            draftNumber = 1
        )
        val chapTitle = Content(
            sort = CHAPTER_TITLE_SORT,
            labelKey = ContentLabel.CHAPTER.value,
            start = 1,
            end = verses,
            selectedTake = null,
            text = null,
            format = FORMAT,
            type = ContentType.TITLE,
            draftNumber = 1
        )
        if (chapter == 1) {
            val bookContent = Content(
                sort = BOOK_TITLE_SORT,
                labelKey = "book",
                start = 1,
                end = verses,
                selectedTake = null,
                text = null,
                format = FORMAT,
                type = ContentType.TITLE,
                draftNumber = 1
            )
            chapterTree.addChild(OtterTreeNode(bookContent))
        }

        chapterTree.addChild(OtterTreeNode(chapTitle))
        chapterTree.addChild(OtterTreeNode(chapChunk))

        for (verse in 1..verses) {
            chapterTree.addChild(OtterTreeNode(verseContent(verse)))
        }
        return chapterTree
    }

    /** An empty row for one verse. */
    fun verseContent(verse: Int) = Content(
        sort = verse,
        labelKey = ContentLabel.VERSE.value,
        start = verse,
        end = verse,
        selectedTake = null,
        text = null,
        format = FORMAT,
        type = ContentType.TEXT,
        draftNumber = 1
    )
}
