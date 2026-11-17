package org.bibletranslationtools.otter.common.data.workbook

/*
 * Typed, opaque identities for the workbook's nodes.
 *
 * The domain only ever asks "is this the same take?"; how storage identifies the row is a
 * persistence detail. Each id wraps the database key, but the constructor and the raw value are
 * internal, and only `persistence/` reads [raw] (a boundary test enforces it). Ids are never written
 * to files, never treated as ordered, and only the store creates them, so a different id scheme
 * would change these types and the store, not the code that uses them.
 */

/** A take. */
@JvmInline
value class TakeId internal constructor(internal val raw: Int)

/** A chapter: the chapter's collection. */
@JvmInline
value class ChapterId internal constructor(internal val raw: Int)

/** One chunk layout of a chapter. */
@JvmInline
value class DraftId internal constructor(internal val raw: Int)

/** Something that has takes: a chapter, a chunk, or a resource's title or body. */
sealed interface ElementId {
    /** The content row the element's takes belong to. */
    val contentRaw: Int
}

/** A chapter's takes, which belong to its meta content row. */
@JvmInline
value class ChapterAudioId internal constructor(override val contentRaw: Int) : ElementId

/** A chunk (or verse), which is a text content row. */
@JvmInline
value class ChunkId internal constructor(override val contentRaw: Int) : ElementId

/** A resource's title or body, which is a content row of its own. */
@JvmInline
value class ResourceComponentId internal constructor(override val contentRaw: Int) : ElementId
