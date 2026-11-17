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
package org.bibletranslationtools.otter.common.persistence.repositories

import io.reactivex.Maybe
import io.reactivex.Single
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.rx2.awaitSingleOrNull
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.api.persistence.repositories.ICollectionRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IContentRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceMetadataRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.ITakeRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookDatabaseAccessors
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.ModelTake
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.ContainerType
import org.bibletranslationtools.otter.common.data.primitives.Content
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.data.primitives.MimeType
import org.bibletranslationtools.otter.common.data.workbook.Book
import org.bibletranslationtools.otter.common.data.workbook.ChapterAudioId
import org.bibletranslationtools.otter.common.data.workbook.ChapterId
import org.bibletranslationtools.otter.common.data.workbook.Resource
import org.bibletranslationtools.otter.common.data.workbook.ResourceComponentId
import org.bibletranslationtools.otter.common.data.workbook.ResourceGroup
import org.bibletranslationtools.otter.common.data.workbook.TextItem
import org.bibletranslationtools.otter.common.data.workbook.Translation
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.data.workbook.WorkbookNodes
import org.bibletranslationtools.otter.common.data.workbook.WorkbookStore
import org.bibletranslationtools.otter.common.data.workbook.WorkbookWriter
import java.util.concurrent.ConcurrentHashMap

/**
 * Opens workbooks: builds a project's node tree over the [WorkbookStore] and keeps one live copy per
 * project, so every screen that opens a project gets the same objects.
 *
 * Nothing here subscribes to anything or saves anything. The nodes save themselves through the store,
 * under the workbook's own writer.
 */
class WorkbookRepository(
    private val directoryProvider: IDirectoryProvider,
    private val db: IWorkbookDatabaseAccessors,
    private val store: WorkbookStore
) : IWorkbookRepository {

    constructor(
        directoryProvider: IDirectoryProvider,
        collectionRepository: ICollectionRepository,
        contentRepository: IContentRepository,
        resourceRepository: IResourceRepository,
        resourceMetadataRepo: IResourceMetadataRepository,
        takeRepository: ITakeRepository,
        store: WorkbookStore
    ) : this(
        directoryProvider,
        WorkbookDatabaseAccessor(
            collectionRepository,
            contentRepository,
            resourceRepository,
            resourceMetadataRepo,
            takeRepository
        ),
        store
    )

    /** The open workbooks, by source and target book collection ids. */
    private val open = ConcurrentHashMap<Pair<Int, Int>, Workbook>()

    override fun get(source: Collection, target: Collection): Workbook =
        open.computeIfAbsent(source.id to target.id) { build(source, target) }

    override suspend fun closeWorkbook(workbook: Workbook) {
        open.values.remove(workbook)
        workbook.close()
    }

    override fun getSoftDeletedTakes(book: Book): Single<List<ModelTake>> =
        db.getSoftDeletedTakes(book.resourceMetadata, book.slug)

    override fun getProjects(): Single<List<Workbook>> {
        return db.getDerivedProjects()
            .map { projects -> projects.filter { it.resourceContainer?.type == ContainerType.Book } }
            .flattenAsObservable { it }
            .flatMapMaybe(::getWorkbook)
            .toList()
    }

    override fun getProjects(translation: Translation): Single<List<Workbook>> {
        return db.getDerivedProjects()
            .map { projects ->
                projects
                    .filter { it.resourceContainer?.type == ContainerType.Book }
                    .filter { it.resourceContainer?.language == translation.target }
                    .filter {
                        val sourceProject = db.getSourceProject(it).blockingGet()
                        sourceProject.resourceContainer?.language == translation.source
                    }
            }
            .flattenAsObservable { it }
            .flatMapMaybe(::getWorkbook)
            .toList()
    }

    override fun getWorkbook(project: Collection): Maybe<Workbook> =
        db.getSourceProject(project).map { sourceProject -> get(sourceProject, project) }

    override suspend fun getSoftDeletedTakesSuspend(book: Book): List<ModelTake> = getSoftDeletedTakes(book).await()
    override suspend fun getProjectsSuspend(): List<Workbook> = getProjects().await()
    override suspend fun getProjectsSuspend(translation: Translation): List<Workbook> = getProjects(translation).await()
    override suspend fun getWorkbookSuspend(project: Collection): Workbook? = getWorkbook(project).awaitSingleOrNull()

    // ── building ──────────────────────────────────────────────────────────────────────────────

    private fun build(source: Collection, target: Collection): Workbook {
        checkNotNull(source.resourceContainer) { "Book collection with id=${source.id} has null resource container" }
        checkNotNull(target.resourceContainer) { "Book collection with id=${target.id} has null resource container" }
        val writer = WorkbookWriter()
        lateinit var nodes: WorkbookNodes
        nodes = WorkbookNodes(store, writer) { chunk ->
            resourceGroups(db.getResourceMetadata(contentStub(chunk.contentRaw)), nodes) { meta ->
                db.getResources(contentStub(chunk.contentRaw), meta)
            }
        }
        return Workbook(directoryProvider, book(source, nodes), book(target, nodes), writer)
    }

    private fun book(bookCollection: Collection, nodes: WorkbookNodes): Book {
        val resourceMetadata = bookCollection.resourceContainer
            ?: throw IllegalStateException("Book collection with id=${bookCollection.id} has null resource container")
        return Book(
            collectionId = bookCollection.id,
            title = bookCollection.titleKey,
            label = bookCollection.labelKey,
            sort = bookCollection.sort,
            slug = bookCollection.slug,
            loadChapters = { chapters(bookCollection, nodes) },
            resourceMetadata = resourceMetadata,
            linkedResources = db.getLinkedResourceMetadata(resourceMetadata),
            subtreeResources = db.getSubtreeResourceMetadata(bookCollection),
            modifiedTs = bookCollection.modifiedTs
        )
    }

    private suspend fun chapters(bookCollection: Collection, nodes: WorkbookNodes) = withContext(Dispatchers.IO) {
        db.getChildren(bookCollection).blockingGet().map { chapterCollection ->
            val meta = db.getCollectionMetaContent(chapterCollection).blockingGet()
            val audioOwner = ChapterAudioId(meta.id)
            nodes.chapter(
                id = ChapterId(chapterCollection.id),
                sort = chapterCollection.sort,
                title = chapterCollection.titleKey,
                label = chapterCollection.labelKey,
                audioOwner = audioOwner,
                audio = store.loadAudio(audioOwner),
                resources = resourceGroups(db.getResourceMetadata(chapterCollection), nodes) { meta ->
                    db.getResources(chapterCollection, meta)
                },
                subtreeResources = db.getSubtreeResourceMetadata(chapterCollection)
            )
        }
    }

    /** One group per resource container, each loading its resources the first time it is read. */
    private fun resourceGroups(
        metadata: List<org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata>,
        nodes: WorkbookNodes,
        contentsOf: (org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata) -> io.reactivex.Observable<Content>
    ): List<ResourceGroup> = metadata.map { meta ->
        ResourceGroup(meta) {
            val contents = withContext(Dispatchers.IO) { contentsOf(meta).toList().blockingGet() }
            resources(contents, meta.identifier, nodes)
        }
    }

    /** Each title, with the body that follows it if there is one. */
    private suspend fun resources(contents: List<Content>, identifier: String, nodes: WorkbookNodes): List<Resource> =
        contents.mapIndexedNotNull { i, item ->
            if (item.type != ContentType.TITLE) return@mapIndexedNotNull null
            val body = contents.getOrNull(i + 1)?.takeIf { it.type == ContentType.BODY }
            Resource(
                title = component(item, ContentType.TITLE, identifier, nodes),
                body = body?.let { component(it, ContentType.BODY, identifier, nodes) }
            )
        }

    private suspend fun component(content: Content, type: ContentType, identifier: String, nodes: WorkbookNodes): Resource.Component {
        val owner = ResourceComponentId(content.id)
        return Resource.Component(
            sort = content.sort,
            textItem = textItem(content),
            audio = nodes.audio(owner, store.loadAudio(owner)),
            contentType = type,
            label = resourceLabel(type, identifier)
        )
    }

    /** The content row with [id], for the resource lookups that take a [Content]; they read only its id. */
    private fun contentStub(id: Int) = Content(
        sort = 0, labelKey = "", start = 0, end = 0, text = null, format = null,
        type = ContentType.TEXT, draftNumber = 0, id = id
    )

    private fun textItem(content: Content): TextItem {
        val format = content.format?.let { runCatching { MimeType.of(it) }.getOrNull() }
        return if (format != null && content.text != null) TextItem(content.text!!, format) else TextItem("", MimeType.USFM)
    }

    private fun resourceLabel(contentType: ContentType, identifier: String): String = when (identifier) {
        "tn" -> when (contentType) {
            ContentType.TITLE -> "snippet"
            ContentType.BODY -> "note"
            else -> ""
        }
        "tq" -> when (contentType) {
            ContentType.TITLE -> "question"
            ContentType.BODY -> "answer"
            else -> ""
        }
        else -> ""
    }
}
