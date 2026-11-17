package org.bibletranslationtools.otter.common.data.workbook

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bibletranslationtools.otter.common.data.primitives.Content
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.data.primitives.MimeType
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.domain.content.Recordable

/**
 * A chapter: its own takes, and its chunks.
 *
 * The chunks load the first time they are asked for ([chunks], [loadedState]); a large book opens
 * without reading every chapter's rows. After that, [state] holds the chapter's active chunk layout
 * as one value: which draft is active and its chunks change together.
 */
class Chapter internal constructor(
    val id: ChapterId,
    override val sort: Int,
    override val title: String,
    override val label: String,
    override val audio: AssociatedAudio,
    override val resources: List<ResourceGroup>,
    override val subtreeResources: List<ResourceMetadata>,
    private val store: WorkbookStore,
    private val writer: WorkbookWriter,
    private val nodes: WorkbookNodes
) : BookElement, BookElementContainer, Recordable {

    override val contentType: ContentType = ContentType.META

    override val textItem: TextItem get() = TextItem("", MimeType.USFM)

    private val _state = MutableStateFlow(ChapterState.NOT_LOADED)

    /** The active chunk layout; [ChapterState.loaded] is false until the chunks are first read. */
    val state: StateFlow<ChapterState> = _state.asStateFlow()

    private val loading = Mutex()

    /** The chapter's state, loading the chunks first if nothing has read them yet. */
    suspend fun loadedState(): ChapterState {
        if (!_state.value.loaded) {
            loading.withLock {
                if (!_state.value.loaded) _state.value = layoutState(store.loadChunks(id))
            }
        }
        return _state.value
    }

    /** The active layout's chunks, loading them if needed. */
    suspend fun chunks(): List<Chunk> = loadedState().chunks

    /** How many text chunks (or verses) the active layout has. */
    suspend fun chunkCount(): Int = chunks().count { it.contentType == ContentType.TEXT }

    /**
     * Deletes the chapter's chunks and their takes. The old [Chunk]s are retired.
     * Replaced by an undoable rechunk in a later step.
     */
    suspend fun resetChunks(): SaveResult<Unit> = writer.save {
        store.clearChunks(id)
        _state.value = ChapterState(activeDraft = null, chunks = emptyList(), loaded = true)
    }

    /** Adds [rows] as the chapter's chunks, linked to their source rows. */
    suspend fun addChunks(rows: List<Content>): SaveResult<Unit> = writer.save {
        store.addChunks(id, rows)
        _state.value = layoutState(store.loadChunks(id))
    }

    fun hasSelectedAudio() = audio.getSelectedTake() != null

    fun getSelectedTake() = audio.getSelectedTake()

    private fun layoutState(layout: ChapterLayout): ChapterState =
        ChapterState(
            activeDraft = layout.draft,
            chunks = layout.chunks.map { record ->
                nodes.chunk(record, isRetired = { _state.value.activeDraft != record.draft })
            },
            loaded = true
        )

    override fun hashCode(): Int = id.hashCode()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Chapter) return false
        return id == other.id
    }

    override fun toString(): String = "Chapter(sort=$sort, title=$title, label=$label)"
}

/** A chapter's active chunk layout: which draft is active and its chunks, as one value. */
data class ChapterState(
    val activeDraft: DraftId?,
    val chunks: List<Chunk>,
    val loaded: Boolean
) {
    companion object {
        val NOT_LOADED = ChapterState(activeDraft = null, chunks = emptyList(), loaded = false)
    }
}
