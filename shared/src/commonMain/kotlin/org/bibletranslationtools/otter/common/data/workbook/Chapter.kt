package org.bibletranslationtools.otter.common.data.workbook

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.data.primitives.CheckingStatus
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
            val loadedNow = loading.withLock {
                if (_state.value.loaded) {
                    false
                } else {
                    _state.value = layoutState(store.loadChunks(id))
                    true
                }
            }
            if (loadedNow) resetStaleChecking(_state.value)
        }
        return _state.value
    }

    /**
     * A checking status belongs to the audio it was given for. Once per load, any chunk whose selected
     * take's audio has changed since (an edit in an external program) goes back to unchecked. Reading
     * a status never writes; this is the one place a stale one is reset.
     */
    private suspend fun resetStaleChecking(state: ChapterState) {
        state.chunks.forEach { chunk ->
            val take = chunk.audio.getSelectedTake() ?: return@forEach
            if (take.checking.status == CheckingStatus.UNCHECKED) return@forEach
            val current = withContext(Dispatchers.IO) { take.checksum() }
            if (current != take.checking.checksum) {
                chunk.audio.setChecking(take, TakeCheckingState(CheckingStatus.UNCHECKED, null))
            }
        }
    }

    /** The active layout's chunks, loading them if needed. */
    suspend fun chunks(): List<Chunk> = loadedState().chunks

    /** How many text chunks (or verses) the active layout has. */
    suspend fun chunkCount(): Int = chunks().count { it.contentType == ContentType.TEXT }

    /**
     * Replaces the chapter's chunks with [rows], as a new layout, in one transaction. The previous
     * layout and its takes are kept, inactive, so this can be undone with [switchDraft] until
     * [settleDrafts] runs. Chunks of the previous layout are retired.
     */
    suspend fun rechunk(rows: List<Content>): SaveResult<RechunkReceipt> {
        // Loaded first, outside the save: loading can itself save (a stale checking status), and the
        // workbook's lock is not reentrant.
        val previous = loadedState().activeDraft ?: DraftId(1)
        return writer.save {
            val next = store.rechunk(id, rows)
            _state.value = layoutState(store.loadChunks(id))
            RechunkReceipt(previous, next)
        }
    }

    /** Makes [to] the active layout: undo and redo of a rechunk. The other layout's chunks are retired. */
    suspend fun switchDraft(to: DraftId): SaveResult<Unit> {
        loadedState()
        return writer.save {
            store.switchDraft(id, to)
            _state.value = layoutState(store.loadChunks(id))
        }
    }

    /**
     * Deletes every layout but the active one, with their takes: what makes a rechunk stick. Called
     * when the user leaves the chapter, when the workbook closes, and at startup.
     */
    suspend fun settleDrafts(): SaveResult<Unit> = writer.save { store.deleteInactiveDrafts(id) }

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
