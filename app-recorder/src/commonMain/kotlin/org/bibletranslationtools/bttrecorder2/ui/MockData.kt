package org.bibletranslationtools.bttrecorder2.ui

import io.reactivex.Single
import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.otter.common.data.primitives.ContainerType
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Chunk
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import org.bibletranslationtools.otter.common.data.primitives.Content
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.data.primitives.MimeType
import org.bibletranslationtools.otter.common.data.workbook.AssociatedAudio
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
import org.bibletranslationtools.otter.common.data.workbook.TextItem
import org.bibletranslationtools.otter.common.data.workbook.WorkbookStore
import org.bibletranslationtools.otter.common.data.workbook.WorkbookWriter
import java.io.File
import java.time.LocalDate

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

    /**
     * The preview project's chapters: three chapters of three verses. Chapter 1's first verse has
     * three takes, the first selected. They load from [PreviewStore], which refuses every change:
     * previews show data, they never save it.
     */
    private val mockChapters: List<Chapter> by lazy {
        val store = PreviewStore()
        val writer = WorkbookWriter()
        (1..3).map { sort ->
            val chapterId = sort * 10
            store.layouts[ChapterId(chapterId)] = ChapterLayout(
                draft = DraftId(1),
                chunks = (1..3).map { verse ->
                    val takes = if (sort == 1 && verse == 1) {
                        (1..3).map { n -> mockTake(n, TakeId(n)) }
                    } else {
                        emptyList()
                    }
                    ChunkRecord(
                        id = ChunkId(chapterId + verse), sort = verse, label = "verse",
                        start = verse, end = verse, bridged = false, contentType = ContentType.TEXT,
                        textItem = TextItem("", MimeType.USFM), draft = DraftId(1),
                        audio = AudioState(takes, takes.firstOrNull()?.id)
                    )
                }
            )
            Chapter(
                id = ChapterId(chapterId), sort = sort, title = "$sort", label = "chapter",
                audio = AssociatedAudio(ChapterAudioId(chapterId), AudioState.EMPTY, store, writer),
                resources = emptyList(), subtreeResources = emptyList(), store = store, writer = writer
            )
        }
    }

    private fun mockTake(number: Int, id: TakeId) = Take(
        name = "take$number.wav",
        file = File("take$number.wav"),
        number = number,
        format = MimeType.WAV,
        createdTimestamp = LocalDate.now(),
        id = id
    )

    /** The preview chapters' chunk layouts. Every change is refused. */
    private class PreviewStore : WorkbookStore {
        val layouts = mutableMapOf<ChapterId, ChapterLayout>()

        override suspend fun loadAudio(owner: ElementId) = AudioState.EMPTY
        override suspend fun loadChunks(chapter: ChapterId) = layouts.getValue(chapter)
        override suspend fun addTake(owner: ElementId, take: Take, select: Boolean): Take = refuse()
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

    /** Chapter [sort] (1 to 3) of the preview project. */
    fun createMockChapter(sort: Int): Chapter =
        mockChapters.first { it.sort == sort }

    /** A verse of chapter 1: verse 1, which has takes, when [hasAudio]; otherwise verse 2. */
    fun createMockChunk(hasAudio: Boolean = false): Chunk =
        runBlocking { createMockChapter(1).chunks()[if (hasAudio) 0 else 1] }

    /** The takes of chapter 1's first verse, in number order. */
    fun createMockTakes(): List<Take> =
        createMockChunk(hasAudio = true).audio.getAllTakes().sortedBy { it.number }

    val mockWorkbooks = listOf(
        createMockWorkbook(1, "gen", "Genesis", englishLanguage),
        createMockWorkbook(2, "exo", "Exodus", englishLanguage),
        createMockWorkbook(3, "lev", "Leviticus", spanishLanguage)
    )
}
