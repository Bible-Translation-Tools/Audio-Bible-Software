package org.bibletranslationtools.otter.common.data.workbook

import org.bibletranslationtools.otter.common.data.primitives.CheckingStatus
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.domain.content.ResourceRecordable

/**
 * A chunk (or verse) of a chapter: one row of the chapter's chunk layout, with its takes.
 *
 * A chunk belongs to one [draft] of its chapter. It is [retired] once that draft is no longer the
 * chapter's active one (after a rechunk, or after an undo of one); a retired chunk's [audio] refuses
 * every change with [RejectReason.RETIRED] instead of writing to a layout nobody sees. Whether it is
 * retired is worked out from the chapter each time, never stored, so it can't disagree with the
 * chapter.
 */
class Chunk internal constructor(
    val id: ChunkId,
    val draft: DraftId,
    override val sort: Int,
    override val label: String,
    val start: Int,
    val end: Int,
    val bridged: Boolean,
    override val contentType: ContentType,
    override val textItem: TextItem,
    override val resources: List<ResourceGroup>,
    private val isRetired: () -> Boolean,
    audioFactory: (gate: () -> RejectReason?) -> AssociatedAudio
) : BookElement, ResourceRecordable {

    override val audio: AssociatedAudio = audioFactory { if (isRetired()) RejectReason.RETIRED else null }

    /** True once this chunk's layout is no longer the chapter's active one. */
    val retired: Boolean get() = isRetired()

    override val title: String
        get() = if (start != end) "$start-$end" else "$start"

    fun hasSelectedAudio(): Boolean = audio.getSelectedTake()?.isDeleted() == false

    /**
     * The selected take's checking status, or unchecked. A pure read: a status saved for audio that
     * has changed since is reset once, when the chapter loads, not here.
     */
    fun checkingStatus(): CheckingStatus = audio.getSelectedTake()?.checking?.status ?: CheckingStatus.UNCHECKED

    override fun equals(other: Any?): Boolean = other is Chunk && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "Chunk(sort=$sort, label=$label, start=$start, end=$end, draft=$draft)"
}
