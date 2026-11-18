package org.bibletranslationtools.otter.common.data.workbook

import org.bibletranslationtools.otter.common.data.primitives.CheckingStatus
import org.bibletranslationtools.otter.common.data.primitives.ContainerType
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.primitives.MimeType
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import io.mockk.mockk
import org.bibletranslationtools.otter.common.domain.resourcecontainer.SourceAudioAccessor
import org.bibletranslationtools.otter.common.domain.resourcecontainer.artwork.ArtworkAccessor
import org.bibletranslationtools.otter.common.domain.resourcecontainer.project.ProjectFilesAccessor
import java.io.File
import java.time.LocalDate

/**
 * Builds a workbook for tests, over a [FakeWorkbookStore] the test can inspect and make fail:
 *
 * ```
 * val workbook = WorkbookBuilder().build {
 *     target {
 *         chapter(1) {
 *             chunk(1..3) { take("chunk1_t1.wav", selected = true) }
 *         }
 *     }
 * }
 * ```
 *
 * The project's files and source audio are relaxed mocks unless a test passes its own.
 */
class WorkbookBuilder(
    val store: FakeWorkbookStore = FakeWorkbookStore(),
    private val projectFilesAccessor: ProjectFilesAccessor = mockk(relaxed = true),
    private val sourceAudioAccessor: SourceAudioAccessor = mockk(relaxed = true)
) {
    fun build(block: WorkbookSpec.() -> Unit): Workbook {
        val spec = WorkbookSpec().apply(block)
        val writer = WorkbookWriter()
        return Workbook(
            book(spec.source ?: BookSpec("gen", "en"), writer),
            book(spec.target ?: BookSpec("gen", "fr"), writer),
            writer,
            sourceAudioAccessor = { sourceAudioAccessor },
            projectFilesAccessor = { projectFilesAccessor },
            artworkAccessor = { mockk<ArtworkAccessor>(relaxed = true) }
        )
    }

    private fun book(spec: BookSpec, writer: WorkbookWriter): Book {
        val chapters = spec.chapters.map { chapter ->
            val chapterId = store.newContentId()
            val audioId = store.newContentId()
            // One state for the store and the node: each audioState call mints new take ids.
            val chapterAudio = audioState(chapter.takes)
            store.putAudio(audioId, chapterAudio)
            store.putLayout(
                chapterId,
                ChapterLayout(
                    draft = DraftId(chapter.draft),
                    chunks = chapter.chunks.map { chunk ->
                        val id = store.newContentId()
                        val state = audioState(chunk.takes)
                        store.putAudio(id, state)
                        ChunkRecord(
                            id = ChunkId(id), sort = chunk.range.first, label = chunk.label,
                            start = chunk.range.first, end = chunk.range.last, bridged = false,
                            contentType = ContentType.TEXT, textItem = TextItem(chunk.text, MimeType.USFM),
                            draft = DraftId(chapter.draft), audio = state
                        )
                    }
                )
            )
            Chapter(
                id = ChapterId(chapterId), sort = chapter.sort, title = "${chapter.sort}", label = "chapter",
                audio = AssociatedAudio(ChapterAudioId(audioId), chapterAudio, store, writer),
                resources = emptyList(), subtreeResources = emptyList(), store = store, writer = writer
            )
        }
        val metadata = metadata(spec.languageSlug)
        return Book(
            collectionId = store.newContentId(), sort = 1, slug = spec.slug, title = spec.slug, label = "book",
            loadChapters = { chapters }, resourceMetadata = metadata, linkedResources = emptyList(),
            modifiedTs = null, subtreeResources = emptyList()
        )
    }

    private fun audioState(takes: List<TakeSpec>): AudioState {
        val saved: List<Pair<Take, TakeSpec>> = takes.mapIndexed { i, take ->
            store.newTake(take.name, i + 1, take.deleted, TakeCheckingState(take.checking, null)) to take
        }
        return AudioState(saved.map { it.first }, saved.firstOrNull { it.second.selected }?.first?.id)
    }

    private fun metadata(languageSlug: String) = ResourceMetadata(
        conformsTo = "rc0.2", creator = "test", description = "", format = "text/usfm", identifier = "ulb",
        issued = LocalDate.now(), language = Language(languageSlug, languageSlug, languageSlug, "ltr", false, ""),
        modified = LocalDate.now(), publisher = "test", subject = "Bible", type = ContainerType.Book,
        title = "test", version = "1", license = "", path = File(".")
    )
}

class WorkbookSpec {
    internal var source: BookSpec? = null
    internal var target: BookSpec? = null

    fun source(slug: String = "gen", language: String = "en", block: BookSpec.() -> Unit = {}) {
        source = BookSpec(slug, language).apply(block)
    }

    fun target(slug: String = "gen", language: String = "fr", block: BookSpec.() -> Unit = {}) {
        target = BookSpec(slug, language).apply(block)
    }
}

class BookSpec internal constructor(internal val slug: String, internal val languageSlug: String) {
    internal val chapters = mutableListOf<ChapterSpec>()

    fun chapter(sort: Int, block: ChapterSpec.() -> Unit = {}) {
        chapters += ChapterSpec(sort).apply(block)
    }
}

class ChapterSpec internal constructor(internal val sort: Int) {
    internal val takes = mutableListOf<TakeSpec>()
    internal val chunks = mutableListOf<ChunkSpec>()

    /** The chunk layout's draft number. */
    var draft: Int = 1

    /** A chapter take. */
    fun take(name: String, selected: Boolean = false, deleted: Boolean = false, checking: CheckingStatus = CheckingStatus.UNCHECKED) {
        takes += TakeSpec(name, selected, deleted, checking)
    }

    /** A chunk covering [range]. */
    fun chunk(range: IntRange, label: String = "chunk", text: String = "", block: ChunkSpec.() -> Unit = {}) {
        chunks += ChunkSpec(range, label, text).apply(block)
    }

    /** A verse row. */
    fun verse(number: Int, text: String = "", block: ChunkSpec.() -> Unit = {}) = chunk(number..number, "verse", text, block)
}

class ChunkSpec internal constructor(internal val range: IntRange, internal val label: String, internal val text: String) {
    internal val takes = mutableListOf<TakeSpec>()

    fun take(name: String, selected: Boolean = false, deleted: Boolean = false, checking: CheckingStatus = CheckingStatus.UNCHECKED) {
        takes += TakeSpec(name, selected, deleted, checking)
    }
}

internal data class TakeSpec(val name: String, val selected: Boolean, val deleted: Boolean, val checking: CheckingStatus)
