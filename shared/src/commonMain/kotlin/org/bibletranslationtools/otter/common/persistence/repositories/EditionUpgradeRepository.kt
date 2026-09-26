package org.bibletranslationtools.otter.common.persistence.repositories

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.bibletranslationtools.otter.common.api.persistence.IProjectDirectories
import org.bibletranslationtools.otter.common.domain.collections.BookRebase
import org.bibletranslationtools.otter.common.domain.collections.ChapterReference
import org.bibletranslationtools.otter.common.domain.collections.ChapterRewrite
import org.bibletranslationtools.otter.common.api.persistence.repositories.IEditionUpgradeRepository
import org.bibletranslationtools.otter.common.domain.collections.ProjectBookState
import org.bibletranslationtools.otter.common.domain.collections.ProjectChapterState
import org.bibletranslationtools.otter.common.domain.collections.SourceBookText
import org.bibletranslationtools.otter.common.domain.collections.SourceChapterText
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.domain.resourcecontainer.RcConstants
import org.bibletranslationtools.otter.common.domain.resourcecontainer.structure.ChapterText
import org.bibletranslationtools.otter.common.domain.resourcecontainer.structure.VerseRange
import org.bibletranslationtools.otter.common.domain.resourcecontainer.structure.VerseText
import org.bibletranslationtools.otter.common.persistence.database.dao.DaoProvider
import org.bibletranslationtools.otter.common.persistence.entities.CollectionEntity
import org.bibletranslationtools.otter.common.persistence.entities.ContentEntity
import org.bibletranslationtools.otter.common.persistence.entities.ResourceMetadataEntity
import org.bibletranslationtools.otter.common.persistence.repositories.mapping.LanguageMapper
import org.bibletranslationtools.otter.common.persistence.repositories.mapping.ResourceMetadataMapper
import org.slf4j.LoggerFactory
import org.wycliffeassociates.resourcecontainer.ResourceContainer
import org.wycliffeassociates.resourcecontainer.entity.Source
import java.io.File

private const val VERSE_LABEL = "verse"
private const val CHUNK_LABEL = "chunk"

class EditionUpgradeRepository(
    private val database: DaoProvider,
    private val collectionRepository: CollectionRepository,
    private val directoryProvider: IProjectDirectories
) : IEditionUpgradeRepository {

    private val logger = LoggerFactory.getLogger(EditionUpgradeRepository::class.java)

    private val collectionDao = database.collectionDao
    private val contentDao = database.contentDao
    private val takeDao = database.takeDao
    private val metadataDao = database.resourceMetadataDao
    private val textType by lazy { database.contentTypeDao.fetchId(ContentType.TEXT) }
    private val metaType by lazy { database.contentTypeDao.fetchId(ContentType.META) }

    override fun projectBook(projectBookId: Int): ProjectBookState? {
        val book = runCatching { collectionDao.fetchById(projectBookId) }.getOrNull() ?: return null
        val derived = metadataDao.fetchById(book.dublinCoreFk ?: return null) ?: return null
        val source = metadataDao.fetchById(derived.derivedFromFk ?: return null) ?: return null
        val chapters = collectionDao.fetchChildren(book).map { chapter ->
            val rows = contentDao.fetchByCollectionId(chapter.id)
            ProjectChapterState(
                chapterId = chapter.id,
                slug = chapter.slug,
                sort = chapter.sort,
                structureEditionId = collectionDao.fetchStructureEdition(chapter.id) ?: source.id,
                hasLiveTakes = rows.any { takeDao.fetchByContentId(it.id, includeDeleted = false).isNotEmpty() },
                hasChunks = rows.any { it.type_fk == textType && it.labelKey == CHUNK_LABEL },
                verses = rows.filter { it.isVerse() }.sortedBy { it.start }.map { it.range() }
            )
        }
        return ProjectBookState(book.id, book.slug, language(derived), metadata(source), chapters)
    }

    override fun sourceBookText(editionId: Int, bookSlug: String): SourceBookText? {
        val book = collectionDao.fetchAll()
            .filter { it.dublinCoreFk == editionId && it.slug == bookSlug }
            .firstOrNull { collectionDao.fetchChildren(it).isNotEmpty() }
            ?: return null
        val chapters = collectionDao.fetchChildren(book).associate { chapter ->
            val verses = contentDao.fetchByCollectionId(chapter.id)
                .filter { it.isVerse() }
                .sortedWith(compareBy({ it.start }, { it.end }))
                .map { VerseText(it.range(), it.text) }
            chapter.sort to SourceChapterText(chapter.id, ChapterText(chapter.slug, bookSlug, chapter.sort, verses))
        }
        return SourceBookText(book.id, chapters)
    }

    override fun chapterUsage(editionId: Int): Int = collectionDao.countEditionUsage(editionId)

    override fun chapterReference(projectBookId: Int, chapterSort: Int): ChapterReference? {
        val book = runCatching { collectionDao.fetchById(projectBookId) }.getOrNull() ?: return null
        val derived = metadataDao.fetchById(book.dublinCoreFk ?: return null) ?: return null
        val source = derived.derivedFromFk ?: return null
        val chapter = collectionDao.fetchChildren(book).firstOrNull { it.sort == chapterSort } ?: return null
        return ChapterReference(book.slug, collectionDao.fetchStructureEdition(chapter.id) ?: source, source)
    }

    override fun apply(rebase: BookRebase) {
        val book = collectionDao.fetchById(rebase.projectBookId)
        val oldDerived = metadataDao.fetchById(book.dublinCoreFk!!)!!
        val oldSource = metadataDao.fetchById(oldDerived.derivedFromFk!!)!!
        val language = language(oldDerived)
        val newDerived = collectionRepository.derivedMetadataFor(rebase.toEdition, language)
        check(newDerived.id == oldDerived.id || collectionDao.fetch(book.slug, newDerived.id) == null) {
            "${book.slug} already has a project in ${language.slug} from that edition"
        }

        // The folder moves first: take paths are rewritten to it in the same transaction as the rest,
        // and the move is undone if that transaction fails. A project in the current layout doesn't move.
        val oldDir = directoryProvider.getProjectDirectory(metadata(oldSource), metadata(oldDerived, language), book.slug)
        val oldPrefix = oldDir.toURI().path
        val newDir = directoryProvider.getProjectDirectory(rebase.toEdition, metadata(newDerived, language), book.slug)
        val moved = oldDir.canonicalFile != newDir.canonicalFile
        if (moved) moveDirectory(oldDir, newDir)

        try {
            database.transaction {
                collectionDao.rebase(book.id, rebase.toSourceBookId, newDerived.id)
                rebase.chapters.forEach { chapter ->
                    val current = collectionDao.fetchById(chapter.chapterId)
                    collectionDao.rebase(chapter.chapterId, chapter.toSourceChapterId ?: current.sourceFk, newDerived.id)
                    // Null means "the book's source edition", which is what a chapter on it should say.
                    collectionDao.setStructureEdition(
                        chapter.chapterId,
                        chapter.structureEditionId.takeIf { it != rebase.toEdition.id }
                    )
                    chapter.rewrite?.let { rewrite(chapter.chapterId, chapter.toSourceChapterId!!, it) }
                }
                val versesPerVerse = rebase.chapters.any { contentDao.fetchByCollectionId(it.chapterId).any { row -> row.isVerse() } }
                rebase.newChapterSourceIds.forEach { addChapter(book, it, newDerived, versesPerVerse) }
                if (moved) takeDao.rewritePathPrefix(book.id, oldPrefix, newDir.toURI().path)
                database.workbookDescriptorDao.rebaseSource(book.id, rebase.toSourceBookId)
                if (newDerived.id != oldDerived.id) {
                    collectionRepository.addToDerivedManifest(newDerived, collectionDao.fetchById(book.id))
                    collectionRepository.removeFromDerivedManifest(oldDerived, book.slug)
                }
            }
        } catch (e: Exception) {
            if (moved) moveDirectory(newDir, oldDir)
            throw e
        }

        updateProjectManifest(newDir, rebase.toEdition)
        rebase.chapters.filter { it.rewrite?.resetChunks == true }
            .forEach { removeFromChunkFile(newDir, collectionDao.fetchById(it.chapterId).sort) }
    }

    /**
     * Gives a chapter the new edition's verse rows. Each old verse's takes (all deleted: a chapter
     * with live takes is never rewritten) follow it to where [ChapterRewrite.groups] says it went, or
     * to the chapter's whole-chapter row when it has no counterpart. Chunks are reset when asked.
     */
    private fun rewrite(chapterId: Int, sourceChapterId: Int, rewrite: ChapterRewrite) {
        val rows = contentDao.fetchByCollectionId(chapterId)
        val sourceRows = contentDao.fetchByCollectionId(sourceChapterId)
        val meta = rows.firstOrNull { it.type_fk == metaType }
        val sourceMeta = sourceRows.firstOrNull { it.type_fk == metaType }
        if (meta != null && sourceMeta != null) {
            meta.start = sourceMeta.start
            meta.end = sourceMeta.end
            contentDao.update(meta)
        }

        val oldVerses = rows.filter { it.isVerse() }
        if (oldVerses.isNotEmpty()) {
            val newVerseIds = sourceRows.filter { it.isVerse() }.associate { sourceVerse ->
                val id = contentDao.insert(
                    ContentEntity(
                        id = 0, sort = sourceVerse.sort, labelKey = VERSE_LABEL, start = sourceVerse.start,
                        end = sourceVerse.end, collectionFk = chapterId, selectedTakeFk = null, text = null,
                        format = null, type_fk = textType, draftNumber = 1, bridged = sourceVerse.bridged
                    )
                )
                contentDao.linkDerivative(id, sourceVerse.id)
                sourceVerse.range() to id
            }
            oldVerses.forEach { old ->
                val destination = rewrite.groups
                    .firstOrNull { old.range() in it.from }
                    ?.to?.firstOrNull()
                    ?.let(newVerseIds::get)
                    ?: meta?.id
                moveTakes(old, destination)
                contentDao.delete(old)
            }
        }
        if (rewrite.resetChunks) {
            rows.filter { it.type_fk == textType && it.labelKey == CHUNK_LABEL }.forEach { chunk ->
                moveTakes(chunk, meta?.id)
                contentDao.delete(chunk)
            }
        }
    }

    private fun moveTakes(row: ContentEntity, destination: Int?) {
        if (destination == null) return
        takeDao.fetchByContentId(row.id, includeDeleted = true).forEach { takeDao.moveToContent(it.id, destination) }
    }

    /** A chapter the new edition has and the project doesn't: created as the project's others were. */
    private fun addChapter(book: CollectionEntity, sourceChapterId: Int, derived: ResourceMetadataEntity, versesPerVerse: Boolean) {
        val source = collectionDao.fetchById(sourceChapterId)
        val chapterId = collectionDao.insert(
            CollectionEntity(
                id = 0, parentFk = book.id, sourceFk = source.id, label = source.label, title = source.title,
                slug = source.slug, sort = source.sort, dublinCoreFk = derived.id, modifiedTs = null
            )
        )
        contentDao.fetchByCollectionId(source.id)
            .filter { versesPerVerse || it.type_fk != textType }
            .forEach { row ->
                val id = contentDao.insert(
                    row.copy(id = 0, collectionFk = chapterId, selectedTakeFk = null, text = null, format = null)
                )
                contentDao.linkDerivative(id, row.id)
            }
    }

    private fun moveDirectory(from: File, to: File) {
        if (to.exists()) {
            check(to.list().isNullOrEmpty()) { "Can't move project to $to: it isn't empty" }
            to.delete()
        }
        to.parentFile?.mkdirs()
        if (!from.renameTo(to)) {
            from.copyRecursively(to, overwrite = false)
            from.deleteRecursively()
        }
        logger.info("Moved project folder $from to $to")
    }

    /** The project's own manifest records its source; point it at the new edition. */
    private fun updateProjectManifest(projectDir: File, edition: ResourceMetadata) {
        if (!projectDir.resolve("manifest.yaml").isFile) return
        runCatching {
            ResourceContainer.load(projectDir).use { container ->
                container.manifest.dublinCore.source = mutableListOf(
                    Source(edition.identifier, edition.language.slug, edition.version)
                )
                container.manifest.dublinCore.version = edition.version
                container.writeManifest()
            }
        }.onFailure { logger.error("Could not update the project manifest in $projectDir", it) }
    }

    private fun removeFromChunkFile(projectDir: File, chapter: Int) {
        val file = projectDir.resolve(RcConstants.CHUNKS_FILE)
        if (!file.isFile) return
        runCatching {
            val chunks = Json.parseToJsonElement(file.readText()) as JsonObject
            if ("$chapter" in chunks) file.writeText(Json.encodeToString(JsonObject.serializer(), JsonObject(chunks - "$chapter")))
        }.onFailure { logger.error("Could not reset chunks for chapter $chapter in $file", it) }
    }

    private fun ContentEntity.isVerse() = type_fk == textType && labelKey == VERSE_LABEL

    private fun ContentEntity.range() = VerseRange(start, maxOf(start, end))

    private fun language(entity: ResourceMetadataEntity) =
        LanguageMapper().mapFromEntity(database.languageDao.fetchById(entity.languageFk)!!)

    private fun metadata(entity: ResourceMetadataEntity, language: org.bibletranslationtools.otter.common.data.primitives.Language = language(entity)) =
        ResourceMetadataMapper().mapFromEntity(entity, language)
}
