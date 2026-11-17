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
class Chapter(
    val id: ChapterId,
    override val sort: Int,
    override val title: String,
    override val label: String,
    override val audio: AssociatedAudio,
    override val resources: List<ResourceGroup>,
    override val subtreeResources: List<ResourceMetadata>,
    private val store: WorkbookStore,
    private val writer: WorkbookWriter,
    /** The resources of the chunk with this id; a chunk built after a rechunk looks its own up. */
    private val chunkResources: (ChunkId) -> List<ResourceGroup> = { emptyList() }
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
     * [settle] runs. Chunks of the previous layout are retired.
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

    // The chapter's undo history: every saved change made to it through [execute], from any of its
    // steps, so a rechunk stays undoable while the user moves between them. Its own lock: an action
    // saves through the workbook's writer, whose lock is not reentrant.
    private val historyLock = Mutex()
    private val history = UndoableActionHistory<IUndoable>()
    private val _canUndo = MutableStateFlow(false)
    private val _canRedo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    /** Runs [action] and, once it is saved, adds it to the chapter's undo history. */
    suspend fun execute(action: IUndoable): SaveResult<Unit> =
        historyLock.withLock { history.execute(action).also { publishHistory() } }

    suspend fun undo(): SaveResult<Unit> = historyLock.withLock { history.undo().also { publishHistory() } }

    suspend fun redo(): SaveResult<Unit> = historyLock.withLock { history.redo().also { publishHistory() } }

    /** The action the next [undo] would undo, if any. */
    fun peekUndo(): IUndoable? = history.peekUndo()

    /**
     * Ends the chapter's undo history and deletes every layout but the active one, with their takes:
     * what makes a rechunk stick. Called when the user leaves the chapter, and at startup.
     */
    suspend fun settle(): SaveResult<Unit> = historyLock.withLock {
        history.clear()
        publishHistory()
        writer.save { store.deleteInactiveDrafts(id) }
    }

    private fun publishHistory() {
        _canUndo.value = history.canUndo()
        _canRedo.value = history.canRedo()
    }

    fun hasSelectedAudio() = audio.getSelectedTake() != null

    fun getSelectedTake() = audio.getSelectedTake()

    private fun layoutState(layout: ChapterLayout): ChapterState =
        ChapterState(
            activeDraft = layout.draft,
            chunks = layout.chunks.map { record ->
                Chunk(
                    id = record.id,
                    draft = record.draft,
                    sort = record.sort,
                    label = record.label,
                    start = record.start,
                    end = record.end,
                    bridged = record.bridged,
                    contentType = record.contentType,
                    textItem = record.textItem,
                    resources = chunkResources(record.id),
                    isRetired = { _state.value.activeDraft != record.draft },
                    audioFactory = { gate -> AssociatedAudio(record.id, record.audio, store, writer, gate) }
                )
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
