package org.bibletranslationtools.otter.common.data.workbook

import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata

/**
 * Builds a workbook's nodes, all wired to the same [store] and [writer]: the one place that knows how
 * a node is put together. The persistence loader and the test builder both build through it, so a
 * test's workbook is assembled exactly as the app's is.
 */
class WorkbookNodes internal constructor(
    private val store: WorkbookStore,
    val writer: WorkbookWriter,
    private val chunkResources: (ChunkId) -> List<ResourceGroup> = { emptyList() }
) {
    fun audio(owner: ElementId, state: AudioState, gate: () -> RejectReason? = { null }): AssociatedAudio =
        AssociatedAudio(owner, state, store, writer, gate)

    fun chunk(record: ChunkRecord, isRetired: () -> Boolean): Chunk =
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
            isRetired = isRetired,
            audioFactory = { gate -> audio(record.id, record.audio, gate) }
        )

    fun chapter(
        id: ChapterId,
        sort: Int,
        title: String,
        label: String,
        audioOwner: ChapterAudioId,
        audio: AudioState,
        resources: List<ResourceGroup> = emptyList(),
        subtreeResources: List<ResourceMetadata> = emptyList()
    ): Chapter = Chapter(
        id = id,
        sort = sort,
        title = title,
        label = label,
        audio = audio(audioOwner, audio),
        resources = resources,
        subtreeResources = subtreeResources,
        store = store,
        writer = writer,
        nodes = this
    )
}
