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

import kotlinx.coroutines.rx2.rxCompletable
import org.bibletranslationtools.otter.common.domain.resourcecontainer.EditionLifecycle
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.schedulers.Schedulers
import org.bibletranslationtools.otter.common.api.persistence.IProjectDirectories
import org.slf4j.LoggerFactory
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.api.persistence.repositories.ICollectionRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IEditionUpgradeRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceMetadataRepository
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookDescriptorRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import java.util.concurrent.TimeUnit

class DeleteProject(
    private val collectionRepository: ICollectionRepository,
    private val directoryProvider: IProjectDirectories,
    private val workbookRepository: IWorkbookRepository,
    private val workbookDescriptorRepo: IWorkbookDescriptorRepository,
    private val editionLifecycle: EditionLifecycle,
    private val upgradeRepository: IEditionUpgradeRepository,
    private val metadataRepository: IResourceMetadataRepository
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun delete(workbook: Workbook, deleteFiles: Boolean): Completable {
        // Order matters here, files won't remove anything from the database
        // delete resources will only remove take entries, but needs derived RCs and links intact
        // delete project may remove derived RCs and links, and thus needs to be last
        val targetProject = workbook.target.toCollection()
        return deleteFiles(workbook, deleteFiles)
            .andThen(collectionRepository.deleteResources(targetProject, deleteFiles))
            .andThen(collectionRepository.deleteProject(targetProject, deleteFiles))
    }

    /**
     * Delete the project's content (files & data). This resets the project
     * to its initial state by deleting and re-inserting the project to its group.
     */
    fun delete(workbookDescriptor: WorkbookDescriptor): Completable {
        return Observable
            .fromCallable {
                workbookRepository.get(
                    workbookDescriptor.sourceCollection,
                    workbookDescriptor.targetCollection
                )
            }
            .flatMapCompletable { workbook ->
                delete(workbook, deleteFiles = true)
            }
            .doOnError {
                logger.error("Error while deleting workbook.", it)
            }
            .andThen(
                recreateWorkbookDescriptor(workbookDescriptor)
            )
            .subscribeOn(Schedulers.io())
    }

    /**
     * Deletes all the projects/workbooks including the derived collections & content. A source
     * edition these projects were the last users of, as their book's edition or the one a
     * held-back chapter keeps its verses from, is then removed if a newer edition of it is
     * installed (see [EditionLifecycle]).
     */
    fun deleteProjects(list: List<WorkbookDescriptor>): Completable {
        // Read before the rows go: the editions held-back chapters keep their verses from.
        var structureEditions = emptySet<Int>()
        return Completable
            .fromAction {
                structureEditions = list
                    .flatMap { upgradeRepository.projectBook(it.targetCollection.id)?.chapters.orEmpty() }
                    .map { it.structureEditionId }
                    .toSet()
            }
            .andThen(Completable.fromAction {
                list.map { workbookRepository.get(it.sourceCollection, it.targetCollection) }
                    .forEach {
                        delete(it, true).blockingAwait() // avoid concurrent accesses to the same file
                    }
            })
            .andThen(workbookDescriptorRepo.delete(list))
            .andThen(Completable.defer { retireSourceEditions(list, structureEditions) })
            .subscribeOn(Schedulers.single()) // sequential execution of delete to avoid db transaction error
    }

    fun deleteProjectsWithTimer(
        books: List<WorkbookDescriptor>,
        timeoutMillis: Int,
        onBeforeDeleteCallback: () -> Unit = {}
    ): Completable {
        return Completable
            .timer(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .andThen {
                onBeforeDeleteCallback()
                it.onComplete()
            }
            .andThen(deleteProjects(books))
    }

    private fun retireSourceEditions(list: List<WorkbookDescriptor>, structureEditions: Set<Int>): Completable =
        rxCompletable {
            val books = list.mapNotNull { it.sourceCollection.resourceContainer }
            val held = metadataRepository.getAllSourcesSuspend().filter { it.id in structureEditions }
            (books + held)
                .distinctBy { it.id }
                .forEach { editionLifecycle.retireIfSuperseded(it) }
        }.onErrorComplete {
            logger.error("Could not retire source editions after deleting projects", it)
            true
        }

    private fun recreateWorkbookDescriptor(workbookDescriptor: WorkbookDescriptor): Completable {
        val sourceMetadata = workbookDescriptor.sourceCollection.resourceContainer!!
        return collectionRepository
            .deriveProject(
                listOf(sourceMetadata),
                workbookDescriptor.sourceCollection,
                workbookDescriptor.targetLanguage,
                workbookDescriptor.mode != ProjectMode.TRANSLATION,
                workbookDescriptor.mode
            )
            .doOnError {
                logger.error("Error while recreating workbook descriptor.", it)
            }
            .ignoreElement()
    }

    private fun deleteFiles(workbook: Workbook, deleteFiles: Boolean): Completable {
        return if (deleteFiles) {
            Completable.fromCallable {
                val source = workbook.source
                val target = workbook.target

                directoryProvider.getProjectDirectory(
                    source = source.resourceMetadata,
                    target = target.resourceMetadata,
                    bookSlug = target.slug
                )
                    .deleteRecursively()

                // delete linked resources project files
                target.linkedResources.forEach {
                    directoryProvider.getProjectDirectory(
                        source = source.resourceMetadata,
                        target = it,
                        bookSlug = target.slug
                    )
                        .deleteRecursively()
                }
            }
        } else {
            Completable.complete()
        }
    }
}
