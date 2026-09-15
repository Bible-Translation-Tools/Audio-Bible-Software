package org.bibletranslationtools.otter.common.domain.narration

import org.bibletranslationtools.otter.common.data.audio.AudioMarker
import org.bibletranslationtools.otter.common.data.audio.BookMarker
import org.bibletranslationtools.otter.common.data.audio.ChapterMarker
import org.bibletranslationtools.otter.common.data.audio.VerseMarker
import org.bibletranslationtools.otter.common.data.primitives.BOOK_TITLE_SORT
import org.bibletranslationtools.otter.common.data.primitives.CHAPTER_TITLE_SORT
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Chunk
import org.bibletranslationtools.otter.common.data.workbook.Workbook

/**
 * The marker narration uses to identify [chunk], at location 0.
 *
 * Narration derives its unit list from a chapter's content rows this way, and matches a stored verse
 * map back onto that list by marker label. Anything that *writes* a verse map has to agree with it
 * exactly or its entries will not be placed, so the mapping is defined once here rather than
 * reproduced per caller.
 */
fun narrationMarkerFor(chunk: Chunk, workbook: Workbook, chapter: Chapter): AudioMarker =
    when (chunk.sort) {
        BOOK_TITLE_SORT -> BookMarker(workbook.source.slug, 0)
        CHAPTER_TITLE_SORT -> ChapterMarker(chapter.sort, 0)
        else -> VerseMarker(chunk.start, chunk.end, 0)
    }
