package org.bibletranslationtools.otter.common.persistence.repositories

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.data.primitives.CheckingStatus
import org.bibletranslationtools.otter.common.data.primitives.Content
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.data.primitives.MimeType
import org.bibletranslationtools.otter.common.data.workbook.AudioState
import org.bibletranslationtools.otter.common.data.workbook.ChapterId
import org.bibletranslationtools.otter.common.data.workbook.ChapterLayout
import org.bibletranslationtools.otter.common.data.workbook.ChunkId
import org.bibletranslationtools.otter.common.data.workbook.ChunkRecord
import org.bibletranslationtools.otter.common.data.workbook.DraftId
import org.bibletranslationtools.otter.common.data.workbook.ElementId
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.otter.common.data.workbook.TakeCheckingState
import org.bibletranslationtools.otter.common.data.workbook.TakeId
import org.bibletranslationtools.otter.common.data.workbook.TextItem
import org.bibletranslationtools.otter.common.data.workbook.WorkbookStore
import org.bibletranslationtools.otter.common.persistence.database.DatabaseWriter
import org.bibletranslationtools.otter.common.persistence.database.dao.DaoProvider
import org.bibletranslationtools.otter.common.persistence.entities.ContentEntity
import org.bibletranslationtools.otter.common.persistence.entities.TakeEntity
import org.bibletranslationtools.otter.common.persistence.repositories.mapping.ContentMapper
import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDate

/**
 * The workbook's [WorkbookStore] over SQLite and the project's files.
 *
 * Writes run on the app-wide [DatabaseWriter] queue, each as one transaction, and touch only the
 * fields the change is about. Files follow the rows: a take's audio is written before its row by the
 * caller (into a file it reserved), and audio is deleted only after its row is gone, so a failure can
 * leave an unused file behind but never a row pointing at a missing one.
 *
 * Reads run on [Dispatchers.IO], off the queue.
 */
class SqlWorkbookStore(
    private val database: DaoProvider,
    private val writer: DatabaseWriter
) : WorkbookStore {

    private val logger = LoggerFactory.getLogger(SqlWorkbookStore::class.java)
    private val takeDao get() = database.takeDao
    private val contentDao get() = database.contentDao
    private val contentMapper by lazy { ContentMapper(database.contentTypeDao) }

    override suspend fun loadAudio(owner: ElementId): AudioState = withContext(Dispatchers.IO) {
        val selected = contentDao.fetchById(owner.contentRaw).selectedTakeFk
        AudioState(
            takes = takeDao.fetchByContentId(owner.contentRaw, includeDeleted = true).map(::toTake).sortedBy { it.number },
            selected = selected?.let(::TakeId)
        )
    }

    override suspend fun loadChunks(chapter: ChapterId): ChapterLayout = withContext(Dispatchers.IO) {
        val text = database.contentTypeDao.fetchId(ContentType.TEXT)
        val title = database.contentTypeDao.fetchId(ContentType.TITLE)
        val rows = contentDao.fetchByCollectionId(chapter.raw)
            .filter { (it.type_fk == text || it.type_fk == title) && !it.bridged }
        val takesByRow = takeDao.fetchByCollectionId(chapter.raw, includeDeleted = true).groupBy { it.contentFk }
        val draft = DraftId(contentDao.activeDraft(chapter.raw) ?: rows.filter { it.type_fk == text }.maxOfOrNull { it.draftNumber } ?: 1)
        ChapterLayout(
            draft = draft,
            chunks = rows.map { row ->
                ChunkRecord(
                    id = ChunkId(row.id),
                    sort = row.sort,
                    label = row.labelKey,
                    start = row.start,
                    end = row.end,
                    bridged = row.bridged,
                    contentType = if (row.type_fk == title) ContentType.TITLE else ContentType.TEXT,
                    textItem = textItem(row),
                    draft = DraftId(row.draftNumber),
                    audio = AudioState(
                        takes = takesByRow[row.id].orEmpty().map(::toTake).sortedBy { it.number },
                        selected = row.selectedTakeFk?.let(::TakeId)
                    )
                )
            }
        )
    }

    override suspend fun addTake(owner: ElementId, take: Take): Take = writer.write {
        val id = takeDao.insert(
            TakeEntity(
                id = 0,
                contentFk = owner.contentRaw,
                filename = take.name,
                filepath = take.file.toURI().path,
                number = take.number,
                createdTs = take.createdTimestamp.toString(),
                deletedTs = null,
                played = 0,
                checkingFk = database.checkingStatusDao.fetchId(take.checking.status),
                checksum = take.checking.checksum
            )
        )
        contentDao.updateSelectedTake(owner.contentRaw, id)
        toTake(takeDao.fetchById(id))
    }

    override suspend fun setSelected(owner: ElementId, take: TakeId?) = writer.write {
        contentDao.updateSelectedTake(owner.contentRaw, take?.raw)
    }

    override suspend fun markDeleted(take: TakeId, on: LocalDate, clearSelectionOf: ElementId?) = writer.write {
        takeDao.updateDeleted(take.raw, on.toString())
        clearSelectionOf?.let { owner ->
            if (contentDao.fetchById(owner.contentRaw).selectedTakeFk == take.raw) {
                contentDao.updateSelectedTake(owner.contentRaw, null)
            }
        }
        Unit
    }

    override suspend fun restore(take: TakeId) = writer.write {
        takeDao.updateDeleted(take.raw, null)
    }

    override suspend fun setChecking(take: TakeId, checking: TakeCheckingState) = writer.write {
        takeDao.updateChecking(take.raw, database.checkingStatusDao.fetchId(checking.status), checking.checksum)
    }

    override suspend fun rechunk(chapter: ChapterId, rows: List<Content>): DraftId = writer.write {
        val text = database.contentTypeDao.fetchId(ContentType.TEXT)
        val textRows = contentDao.fetchAllDraftsByCollectionId(chapter.raw).filter { it.type_fk == text }
        val active = contentDao.activeDraft(chapter.raw)
        // Past the active draft too: it has no rows when the user removed every chunk marker.
        val next = (textRows.maxOfOrNull { it.draftNumber } ?: 0).coerceAtLeast(active ?: 0) + 1
        insertLinked(chapter, rows, draft = next)
        contentDao.setActiveDraft(chapter.raw, next)
        DraftId(next)
    }

    override suspend fun switchDraft(chapter: ChapterId, to: DraftId) = writer.write {
        contentDao.setActiveDraft(chapter.raw, to.raw)
    }

    override suspend fun deleteInactiveDrafts(chapter: ChapterId) {
        val files = writer.write {
            val active = contentDao.activeDraft(chapter.raw) ?: return@write emptyList()
            val text = database.contentTypeDao.fetchId(ContentType.TEXT)
            val inactive = contentDao.fetchAllDraftsByCollectionId(chapter.raw)
                .filter { it.type_fk == text && it.draftNumber != active }
            // Takes are deleted explicitly: databases migrated from before version 13 have no
            // take-to-content cascade.
            val takes = inactive.flatMap { takeDao.fetchByContentId(it.id, includeDeleted = true) }
            takes.forEach { takeDao.delete(it) }
            inactive.forEach { contentDao.delete(it) }
            takes.map { File(it.filepath) }
        }
        withContext(Dispatchers.IO) {
            files.forEach { file ->
                if (file.exists() && !file.delete()) logger.warn("Could not delete the audio of a settled draft's take: $file")
            }
        }
    }

    override suspend fun chaptersWithDrafts(): List<ChapterId> =
        withContext(Dispatchers.IO) { contentDao.chaptersWithDrafts().map(::ChapterId) }

    /** Inserts [rows] into [chapter] under [draft], each linked to its source row. */
    private fun insertLinked(chapter: ChapterId, rows: List<Content>, draft: Int) {
        val sources = database.collectionDao.fetchById(chapter.raw).sourceFk
            ?.let { contentDao.fetchByCollectionId(it) }
            .orEmpty()
        rows.forEach { content ->
            content.draftNumber = draft
            val id = contentDao.insert(contentMapper.mapToEntity(content, chapter.raw).apply { collectionFk = chapter.raw })
            content.id = id
            val type = database.contentTypeDao.fetchId(content.type)
            sources.filter { it.labelKey == content.labelKey && it.type_fk == type }
                .forEach { contentDao.linkDerivative(id, it.id) }
        }
    }

    private fun toTake(entity: TakeEntity) = Take(
        id = TakeId(entity.id),
        name = entity.filename,
        file = File(entity.filepath),
        number = entity.number,
        format = MimeType.WAV,
        createdTimestamp = LocalDate.parse(entity.createdTs),
        deletedOn = entity.deletedTs?.let(LocalDate::parse),
        checking = TakeCheckingState(
            database.checkingStatusDao.fetchById(entity.checkingFk) ?: CheckingStatus.UNCHECKED,
            entity.checksum
        )
    )

    private fun textItem(row: ContentEntity): TextItem {
        return row.format?.let { format ->
            row.text?.let { text ->
                TextItem(text, MimeType.of(format))
            }
        } ?: TextItem("", MimeType.USFM)
    }
}
