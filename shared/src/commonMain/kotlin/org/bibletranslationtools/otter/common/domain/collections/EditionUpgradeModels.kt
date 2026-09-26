package org.bibletranslationtools.otter.common.domain.collections

import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.domain.resourcecontainer.structure.ChapterText
import org.bibletranslationtools.otter.common.domain.resourcecontainer.structure.VerseGroup
import org.bibletranslationtools.otter.common.domain.resourcecontainer.structure.VerseRange

/** A project book as moving it to another source edition needs to see it. */
data class ProjectBookState(
    val bookId: Int,
    val slug: String,
    val targetLanguage: Language,
    /** The source edition the book currently points at. */
    val sourceEdition: ResourceMetadata,
    val chapters: List<ProjectChapterState>
)

/**
 * One chapter of a project book.
 *
 * @property structureEditionId the source edition its verse rows came from: the book's source
 *   edition unless the chapter was held back on an earlier upgrade.
 * @property hasLiveTakes a take that isn't deleted, on any of its verses, chunks or the chapter.
 * @property hasChunks it has chunk rows (chunked translation mode).
 * @property verses its verse rows' ranges; empty for a chapter recorded as a whole.
 */
data class ProjectChapterState(
    val chapterId: Int,
    val slug: String,
    val sort: Int,
    val structureEditionId: Int,
    val hasLiveTakes: Boolean,
    val hasChunks: Boolean,
    val verses: List<VerseRange>
)

/** A source edition's book: its collection and each chapter's collection and text, by chapter number. */
data class SourceBookText(val bookId: Int, val chapters: Map<Int, SourceChapterText>)

data class SourceChapterText(val chapterId: Int, val text: ChapterText)

/** Moving project book [projectBookId] to source book [toSourceBookId] of edition [toEdition]. */
data class BookRebase(
    val projectBookId: Int,
    val toEdition: ResourceMetadata,
    val toSourceBookId: Int,
    val chapters: List<ChapterRebase>,
    /** Chapters the new edition has and the project doesn't, by source chapter id. */
    val newChapterSourceIds: List<Int>
)

/**
 * One chapter's part in a [BookRebase].
 *
 * @property toSourceChapterId the new edition's chapter it now refers to; null keeps its current one.
 * @property structureEditionId the edition its verse structure comes from after the move.
 * @property rewrite set when the chapter takes on the new edition's verse structure.
 */
data class ChapterRebase(
    val chapterId: Int,
    val toSourceChapterId: Int?,
    val structureEditionId: Int,
    val rewrite: ChapterRewrite?
)

/**
 * Replacing a chapter's verse rows with the new edition's. [groups] say where each old verse went,
 * so its takes (all deleted ones: a chapter with live takes is never rewritten) can follow it.
 */
data class ChapterRewrite(val groups: List<VerseGroup>, val resetChunks: Boolean)

/**
 * Which editions a project chapter's structure and reference come from. They differ for a chapter
 * held back on an upgrade.
 */
data class ChapterReference(val bookSlug: String, val structureEditionId: Int, val referenceEditionId: Int)
