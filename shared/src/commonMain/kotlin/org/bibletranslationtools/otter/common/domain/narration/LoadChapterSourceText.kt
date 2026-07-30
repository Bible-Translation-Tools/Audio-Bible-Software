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
package org.bibletranslationtools.otter.common.domain.narration

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.data.workbook.Workbook

/**
 * Reads a chapter's scripture text from the workbook's SOURCE book.
 *
 * The teleprompter shows source text, not target: the target project is what the user is about to
 * narrate and has no text of its own. So this matches the source book's chapter by sort and drains
 * its chunks, keyed both by verse label (for looking up one verse) and in order (for the
 * whole-chapter view).
 *
 * Reads through the [Workbook] object rather than a repository — the chunk observables are already
 * hanging off it — but it is still database-backed work behind those observables, hence
 * [Dispatchers.IO].
 */
class LoadChapterSourceText {

    /**
     * @param byVerseLabel verse label (`"3"`, `"3-4"`) to that verse's text
     * @param inOrder every chunk's text, in chunk order
     */
    data class ChapterSourceText(
        val byVerseLabel: Map<String, String>,
        val inOrder: List<String>
    ) {
        companion object {
            /** No source text available — the teleprompter renders empty rather than failing. */
            val EMPTY = ChapterSourceText(emptyMap(), emptyList())
        }
    }

    suspend fun execute(workbook: Workbook, chapterSort: Int): ChapterSourceText =
        withContext(Dispatchers.IO) {
            val sourceChunks = workbook.source.chapters.toList().await()
                .firstOrNull { it.sort == chapterSort }
                ?.chunksSuspend()
                .orEmpty()
            ChapterSourceText(
                byVerseLabel = sourceChunks.associate { it.title to it.textItem.text },
                inOrder = sourceChunks.map { it.textItem.text }
            )
        }
}
