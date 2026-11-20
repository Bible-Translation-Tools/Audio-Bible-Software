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
package org.bibletranslationtools.otter.common.domain.collections

import org.bibletranslationtools.otter.common.domain.resourcecontainer.EditionOrder
import io.reactivex.Completable
import io.reactivex.Single
import kotlinx.coroutines.rx2.rxCompletable
import kotlinx.coroutines.rx2.rxSingle
import org.bibletranslationtools.otter.common.api.persistence.repositories.ICollectionRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceMetadataRepository
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata

class CreateProject(
    private val collectionRepo: ICollectionRepository,
    private val resourceMetadataRepo: IResourceMetadataRepository,
    private val translationCreation: CreateTranslation
) {

    /**
     * Create derived collections for each source RC that has content in sourceProject's subtree, optionally
     * limited to resourceId (if not null).
     *
     * @param sourceProject The Book Collection to derive from
     * @param targetLanguage The language of the derived project
     * @param resourceId Filters the source and linked RCs by this optional filter
     * @param deriveProjectFromVerses Derives Content/Chunks for Chapters based on the Verses parsed in the text.
     */
    fun create(
        sourceProject: Collection,
        targetLanguage: Language,
        mode: ProjectMode? = null,
        resourceId: String? = null,
        deriveProjectFromVerses: Boolean = false
    ): Single<Collection> =
        rxSingle { createSuspend(sourceProject, targetLanguage, mode, resourceId, deriveProjectFromVerses) }

    /** Suspending [create]. */
    suspend fun createSuspend(
        sourceProject: Collection,
        targetLanguage: Language,
        mode: ProjectMode? = null,
        resourceId: String? = null,
        deriveProjectFromVerses: Boolean = false
    ): Collection {
        // The source RC and its linked (help) RCs, limited to resourceId when one is given.
        val sourceRc = sourceProject.resourceContainer
            ?: throw NullPointerException("Source project has no metadata")
        val matchingRcs = (listOf(sourceRc) + resourceMetadataRepo.getLinkedSuspend(sourceRc))
            .filter { resourceId == null || resourceId == it.identifier }

        val projectMode = when {
            mode != null -> mode
            sourceRc.language == targetLanguage -> ProjectMode.NARRATION
            else -> ProjectMode.TRANSLATION
        }

        return collectionRepo.deriveProjectSuspend(
            matchingRcs, sourceProject, targetLanguage, deriveProjectFromVerses, projectMode
        )
    }

    /**
     * Derives a project for every book of the source in [sourceLanguage] (of [resourceId], when
     * given) into [targetLanguage], from [edition] when given and otherwise from the newest edition.
     * A book the language pair already has a project for, from any edition of that source, is left
     * alone: it keeps the edition it is on, and doesn't get a second, empty project.
     */
    fun createAllBooks(
        sourceLanguage: Language,
        targetLanguage: Language,
        projectMode: ProjectMode,
        resourceId: String? = null,
        edition: ResourceMetadata? = null
    ): Completable =
        rxCompletable { createAllBooksSuspend(sourceLanguage, targetLanguage, projectMode, resourceId, edition) }

    /** Suspending [createAllBooks]. */
    suspend fun createAllBooksSuspend(
        sourceLanguage: Language,
        targetLanguage: Language,
        projectMode: ProjectMode,
        resourceId: String? = null,
        edition: ResourceMetadata? = null
    ) {
        val isVerseByVerse = projectMode != ProjectMode.TRANSLATION
        val rootCollection = collectionRepo.getRootSourcesSuspend()
            .filter { collection ->
                collection.resourceContainer?.language == sourceLanguage &&
                    (resourceId?.let { collection.resourceContainer?.identifier == resourceId } ?: true) &&
                    (edition?.let { collection.resourceContainer?.id == it.id } ?: true)
            }
            // Several editions of a source may be installed; new projects use the newest.
            .sortedWith(EditionOrder.newestFirstBy { it.resourceContainer })
            .firstOrNull()
            ?: throw NoSuchElementException("No source in ${sourceLanguage.slug} to create books from")
        val translated = booksWithProjects(rootCollection.resourceContainer!!, targetLanguage)
        collectionRepo.getChildrenSuspend(rootCollection)
            .filter { it.slug !in translated }
            .forEach { book ->
                collectionRepo.deriveProjectSuspend(
                    listOf(book.resourceContainer!!), book, targetLanguage, isVerseByVerse, projectMode
                )
            }
        translationCreation.createSuspend(sourceLanguage, targetLanguage)
    }

    /** Slugs of the books with a project in [targetLanguage] from any installed edition of [source]'s source. */
    private suspend fun booksWithProjects(source: ResourceMetadata, targetLanguage: Language): Set<String> {
        val derived = resourceMetadataRepo.getAllSourcesSuspend()
            .filter { it.language.slug == source.language.slug && it.identifier == source.identifier }
            .flatMap { resourceMetadataRepo.getAllDerivativesSuspend(it) }
            .filter { it.language.slug == targetLanguage.slug }
            .map { it.id }
            .toSet()
        if (derived.isEmpty()) return emptySet()
        return collectionRepo.getDerivedProjectsSuspend()
            .filter { it.resourceContainer?.id in derived }
            .map { it.slug }
            .toSet()
    }
}
