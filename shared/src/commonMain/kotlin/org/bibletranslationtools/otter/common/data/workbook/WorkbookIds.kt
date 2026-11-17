package org.bibletranslationtools.otter.common.data.workbook

/*
 * Typed, opaque identities for the workbook's nodes.
 *
 * The domain only ever asks "is this the same take?"; how storage identifies the row is a
 * persistence detail. Each id wraps the database key; the raw value is internal, so only `:shared`
 * reads it. Ids are never written to files and never treated as ordered.
 */

/** A take. */
@JvmInline
value class TakeId(internal val raw: Int)

/** A chapter: the chapter's collection. */
@JvmInline
value class ChapterId(internal val raw: Int)

/** One chunk layout of a chapter. */
@JvmInline
value class DraftId(internal val raw: Int)

/** Something that has takes: a chapter, a chunk, or a resource's title or body. */
sealed interface ElementId {
    /** The content row the element's takes belong to. */
    val contentRaw: Int
}

/** A chapter's takes, which belong to its meta content row. */
@JvmInline
value class ChapterAudioId(override val contentRaw: Int) : ElementId

/** A chunk (or verse), which is a text content row. */
@JvmInline
value class ChunkId(override val contentRaw: Int) : ElementId

/** A resource's title or body, which is a content row of its own. */
@JvmInline
value class ResourceComponentId(override val contentRaw: Int) : ElementId
