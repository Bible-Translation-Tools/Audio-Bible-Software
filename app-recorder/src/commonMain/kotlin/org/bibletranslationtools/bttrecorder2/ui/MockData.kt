package org.bibletranslationtools.bttrecorder2.ui

import io.reactivex.Single
import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.otter.common.data.primitives.ContainerType
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.primitives.MimeType
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.workbook.AssociatedAudio
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Chunk
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.otter.common.data.workbook.TextItem
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import org.bibletranslationtools.otter.common.data.primitives.Content
import org.bibletranslationtools.otter.common.data.workbook.AudioState
import org.bibletranslationtools.otter.common.data.workbook.ChapterAudioId
import org.bibletranslationtools.otter.common.data.workbook.ChapterId
import org.bibletranslationtools.otter.common.data.workbook.ChapterLayout
import org.bibletranslationtools.otter.common.data.workbook.ChunkId
import org.bibletranslationtools.otter.common.data.workbook.ChunkRecord
import org.bibletranslationtools.otter.common.data.workbook.DraftId
import org.bibletranslationtools.otter.common.data.workbook.ElementId
import org.bibletranslationtools.otter.common.data.workbook.TakeCheckingState
import org.bibletranslationtools.otter.common.data.workbook.TakeId
import org.bibletranslationtools.otter.common.data.workbook.WorkbookStore
import org.bibletranslationtools.otter.common.data.workbook.WorkbookWriter
import java.io.File
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

object MockData {
    val englishLanguage = Language(
        slug = "eng",
        name = "English",
        anglicizedName = "English",
        direction = "ltr",
        isGateway = true,
        region = "US"
    )

    val spanishLanguage = Language(
        slug = "spa",
        name = "Español",
        anglicizedName = "Spanish",
        direction = "ltr",
        isGateway = true,
        region = "ES"
    )

    fun createMockCollection(slug: String, label: String, language: Language) = Collection(
        sort = 1,
        slug = slug,
        labelKey = label,
        titleKey = label,
        resourceContainer = ResourceMetadata(
            conformsTo = "rc0.2",
            creator = "WA",
            description = "Mock",
            format = "audio/wav",
            identifier = slug,
            issued = LocalDate.now(),
            language = language,
            modified = LocalDate.now(),
            publisher = "WA",
            subject = "Bible",
            type = ContainerType.Bundle,
            title = label,
            version = "1",
            license = "CC BY-SA 4.0",
            path = File("")
        )
    )

    fun createMockWorkbook(id: Int, slug: String, label: String, language: Language) =
        WorkbookDescriptor(
            id = id,
            sourceCollection = createMockCollection(slug, label, language),
            targetCollection = createMockCollection(slug, label, language),
            mode = ProjectMode.TRANSLATION,
            progress = Single.just(0.5),
            hasSourceAudio = true
        )

    fun createMockTake(number: Int, name: String = "Take $number") = Take(
        name = name,
        file = File("mock/path/$name.wav"),
        number = number,
        format = MimeType.WAV!!,
        createdTimestamp = LocalDate.now(),
        id = TakeId(number)
    )

    fun createMockChunk(sort: Int, label: String, hasAudio: Boolean = false): Chunk {
        // A chunk is built by its chapter, so this one comes from a chapter of its own.
        val chapter = mockChapter(
            sort = 1,
            title = "1",
            label = "1",
            chunks = listOf(mockChunkRecord(sort, label, if (hasAudio) listOf(createMockTake(1)) else emptyList()))
        )
        return runBlocking { chapter.chunks().single() }
    }

    fun createMockChapter(sort: Int, title: String, label: String, chunkCount: Int = 1) = mockChapter(
        sort = sort,
        title = title,
        label = label,
        chunks = (1..chunkCount).map {
            mockChunkRecord(
                it,
                it.toString()
            )
        }
    )

    private val previewStore = PreviewStore()
    private val previewWriter = WorkbookWriter()
    private val lastId = AtomicInteger()

    private fun mockChunkRecord(sort: Int, label: String, takes: List<Take> = emptyList()) = ChunkRecord(
        id = ChunkId(lastId.incrementAndGet()),
        sort = sort,
        label = label,
        start = sort,
        end = sort,
        bridged = false,
        contentType = ContentType.TEXT,
        textItem = TextItem("Mock Text", MimeType.USFM!!),
        draft = DraftId(1),
        audio = AudioState(takes, takes.firstOrNull()?.id)
    )

    private fun mockChapter(sort: Int, title: String, label: String, chunks: List<ChunkRecord>): Chapter {
        val id = lastId.incrementAndGet()
        previewStore.layouts[ChapterId(id)] = ChapterLayout(draft = DraftId(1), chunks = chunks)
        return Chapter(
            id = ChapterId(id),
            sort = sort,
            title = title,
            label = label,
            audio = AssociatedAudio(ChapterAudioId(id), AudioState.EMPTY, previewStore, previewWriter),
            resources = emptyList(),
            subtreeResources = emptyList(),
            store = previewStore,
            writer = previewWriter
        )
    }

    /** The preview chapters' chunk layouts. Every change is refused. */
    private class PreviewStore : WorkbookStore {
        val layouts = mutableMapOf<ChapterId, ChapterLayout>()

        override suspend fun loadAudio(owner: ElementId) = AudioState.EMPTY
        override suspend fun loadChunks(chapter: ChapterId) = layouts.getValue(chapter)
        override suspend fun addTake(owner: ElementId, take: Take): Take = refuse()
        override suspend fun setSelected(owner: ElementId, take: TakeId?) = refuse()
        override suspend fun markDeleted(take: TakeId, on: LocalDate, clearSelectionOf: ElementId?) = refuse()
        override suspend fun restore(take: TakeId) = refuse()
        override suspend fun setChecking(take: TakeId, checking: TakeCheckingState) = refuse()
        override suspend fun rechunk(chapter: ChapterId, rows: List<Content>): DraftId = refuse()
        override suspend fun switchDraft(chapter: ChapterId, to: DraftId) = refuse()
        override suspend fun deleteInactiveDrafts(chapter: ChapterId) = refuse()
        override suspend fun chaptersWithDrafts(): List<ChapterId> = emptyList()

        private fun refuse(): Nothing = throw UnsupportedOperationException("Previews do not save.")
    }

    val mockWorkbooks = listOf(
        createMockWorkbook(1, "gen", "Genesis", englishLanguage),
        createMockWorkbook(2, "exo", "Exodus", englishLanguage),
        createMockWorkbook(3, "lev", "Leviticus", spanishLanguage)
    )
}
