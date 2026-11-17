package org.bibletranslationtools.otter.common.data.workbook.fixture

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
import org.bibletranslationtools.otter.common.data.workbook.NewTake
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.otter.common.data.workbook.TakeCheckingState
import org.bibletranslationtools.otter.common.data.workbook.TakeId
import org.bibletranslationtools.otter.common.data.workbook.TextItem
import org.bibletranslationtools.otter.common.data.workbook.WorkbookStore
import java.time.LocalDate

/**
 * An in-memory [WorkbookStore] for tests. It keeps what was saved, records every call in [calls],
 * and can be told to fail the next write with [failNextWrite].
 *
 * Build workbooks over it with [WorkbookBuilder]; it is the store the builder fills.
 */
class FakeWorkbookStore : WorkbookStore {

    private val audio = mutableMapOf<Int, AudioState>()
    private val layouts = mutableMapOf<Int, ChapterLayout>()
    private var nextTake = 1
    private var nextContent = 1
    private var failure: Throwable? = null

    /** Every call, in order, by name. */
    val calls = mutableListOf<String>()

    /** The next write throws [error] instead of saving. */
    fun failNextWrite(error: Throwable = IllegalStateException("simulated save failure")) {
        failure = error
    }

    /** What is saved for [owner] now. */
    fun saved(owner: ElementId): AudioState = audio[owner.contentRaw] ?: AudioState.EMPTY

    // ── filling, for the builder ──────────────────────────────────────────────────────────────

    internal fun newContentId(): Int = nextContent++

    internal fun newTake(name: String, number: Int, deleted: Boolean, checking: TakeCheckingState): Take =
        Take(
            id = TakeId(nextTake++),
            name = name,
            file = java.io.File(name),
            number = number,
            format = MimeType.WAV,
            createdTimestamp = LocalDate.now(),
            deletedOn = if (deleted) LocalDate.now() else null,
            checking = checking
        )

    internal fun putAudio(contentRaw: Int, state: AudioState) {
        audio[contentRaw] = state
    }

    internal fun putLayout(chapter: Int, layout: ChapterLayout) {
        layouts[chapter] = layout
    }

    // ── WorkbookStore ─────────────────────────────────────────────────────────────────────────

    override suspend fun loadAudio(owner: ElementId): AudioState {
        calls += "loadAudio"
        return saved(owner)
    }

    override suspend fun loadChunks(chapter: ChapterId): ChapterLayout {
        calls += "loadChunks"
        val layout = layouts[chapter.raw] ?: ChapterLayout(DraftId(1), emptyList())
        return layout.copy(chunks = layout.chunks.map { it.copy(audio = audio[it.id.contentRaw] ?: it.audio) })
    }

    override suspend fun addTake(owner: ElementId, take: NewTake, select: Boolean): Take = write("addTake") {
        val saved = Take(TakeId(nextTake++), take.name, take.file, take.number, take.format, take.createdOn, null, take.checking)
        val now = saved(owner)
        audio[owner.contentRaw] = now.copy(takes = now.takes + saved, selected = if (select) saved.id else now.selected)
        saved
    }

    override suspend fun setSelected(owner: ElementId, take: TakeId?) = write("setSelected") {
        audio[owner.contentRaw] = saved(owner).copy(selected = take)
    }

    override suspend fun markDeleted(take: TakeId, on: LocalDate, clearSelectionOf: ElementId?) = write("markDeleted") {
        updateTake(take) { it.copy(deletedOn = on) }
        clearSelectionOf?.let { owner ->
            val now = saved(owner)
            if (now.selected == take) audio[owner.contentRaw] = now.copy(selected = null)
        }
        Unit
    }

    override suspend fun restore(take: TakeId) = write("restore") {
        updateTake(take) { it.copy(deletedOn = null) }
    }

    override suspend fun setChecking(take: TakeId, checking: TakeCheckingState) = write("setChecking") {
        updateTake(take) { it.copy(checking = checking) }
    }

    private val inactive = mutableMapOf<Int, MutableMap<Int, ChapterLayout>>()

    override suspend fun rechunk(chapter: ChapterId, rows: List<Content>): DraftId = write("rechunk") {
        val current = layouts[chapter.raw] ?: ChapterLayout(DraftId(1), emptyList())
        inactive.getOrPut(chapter.raw) { mutableMapOf() }[current.draft.raw] = current
        val next = DraftId((inactive[chapter.raw]!!.keys + current.draft.raw).max() + 1)
        layouts[chapter.raw] = ChapterLayout(next, rows.map { row ->
            val id = newContentId().also { row.id = it }
            ChunkRecord(
                id = ChunkId(id), sort = row.sort, label = row.labelKey, start = row.start, end = row.end,
                bridged = row.bridged, contentType = row.type, textItem = TextItem(row.text ?: "", MimeType.USFM),
                draft = next, audio = AudioState.EMPTY
            )
        })
        next
    }

    override suspend fun switchDraft(chapter: ChapterId, to: DraftId) = write("switchDraft") {
        val drafts = inactive.getOrPut(chapter.raw) { mutableMapOf() }
        val current = layouts[chapter.raw]
        val target = drafts.remove(to.raw) ?: error("no draft ${to.raw} for chapter ${chapter.raw}")
        current?.let { drafts[it.draft.raw] = it }
        layouts[chapter.raw] = target
    }

    override suspend fun deleteInactiveDrafts(chapter: ChapterId) = write("deleteInactiveDrafts") {
        inactive.remove(chapter.raw)?.values?.forEach { layout -> layout.chunks.forEach { audio.remove(it.id.contentRaw) } }
        Unit
    }

    override suspend fun chaptersWithDrafts(): List<ChapterId> = inactive.keys.map(::ChapterId)

    private fun updateTake(id: TakeId, change: (Take) -> Take) {
        audio.entries.forEach { (key, state) ->
            if (state.takes.any { it.id == id }) {
                audio[key] = state.copy(takes = state.takes.map { if (it.id == id) change(it) else it })
            }
        }
    }

    private fun <T> write(name: String, block: () -> T): T {
        calls += name
        failure?.let { failure = null; throw it }
        return block()
    }
}
