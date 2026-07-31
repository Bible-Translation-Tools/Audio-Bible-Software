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
package org.bibletranslationtools.otter.common.domain.project

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookDescriptorRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Workbook

/**
 * Resolves a workbook descriptor id into the open workbook, its project mode, and its chapters.
 *
 * Screens are navigated to with a descriptor id and nothing else, so every one of them had to
 * repeat the same three steps: look the descriptor up, ask the workbook repository for the
 * (source, target) pair it names, then drain the target book's chapters off its Rx observable.
 * Four of Orature's ViewModels — narration, translation, contributor and export — each carried a
 * copy, two of them also sorting the chapters and one deriving the completion map.
 *
 * All of it is repository orchestration with no screen state in it, which is why it lives here
 * rather than in either app.
 *
 * @param ioDispatcher where the repository work runs. Injected rather than hard-coded so a test can
 *   supply its own scheduler. With `Dispatchers.IO` baked in, this work escaped the caller's test
 *   dispatcher and the only way to wait for it was a wall-clock timeout — which is what made the
 *   Orature ViewModel tests flaky.
 */
class OpenWorkbook(
    private val workbookDescriptorRepository: IWorkbookDescriptorRepository,
    private val workbookRepository: IWorkbookRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    data class OpenedProject(
        val workbook: Workbook,
        val mode: ProjectMode
    )

    /**
     * @param chapters the target book's chapters, ordered by [Chapter.sort]
     * @param completedByChapterSort whether each chapter has a selected take, keyed by sort
     */
    data class OpenedWorkbook(
        val workbook: Workbook,
        val mode: ProjectMode,
        val chapters: List<Chapter>,
        val completedByChapterSort: Map<Int, Boolean>
    )

    /**
     * Resolves the descriptor to its workbook and project mode, and nothing else.
     *
     * Screens that do not show a chapter list — the contributor editor, for one — want exactly
     * this: [openWithChapters] costs a [Chapter.hasSelectedAudio] lookup per chapter, which is a
     * per-chapter query in service of a list those screens never render.
     *
     * @throws IllegalStateException if no descriptor has this id, which means the caller was
     *   navigated to with an id that no longer exists — not something a screen can recover from.
     */
    suspend fun open(workbookDescriptorId: Int): OpenedProject = withContext(ioDispatcher) {
        val descriptor = workbookDescriptorRepository.getByIdSuspend(workbookDescriptorId)
            ?: error("No workbook descriptor with id=$workbookDescriptorId")
        OpenedProject(
            workbook = workbookRepository.get(
                descriptor.sourceCollection,
                descriptor.targetCollection
            ),
            mode = descriptor.mode
        )
    }

    /**
     * [open], plus the target book's chapters in sort order and each one's completion.
     *
     * Runs entirely on [ioDispatcher]: the repositories hit the database, and
     * [Chapter.hasSelectedAudio] resolves each chapter's selected take, which is cheap per call
     * but not free across a whole book — it should not be sampled on the main thread.
     */
    suspend fun openWithChapters(workbookDescriptorId: Int): OpenedWorkbook =
        withContext(ioDispatcher) {
            val opened = open(workbookDescriptorId)
            val chapters = opened.workbook.target.chapters.toList().await().sortedBy { it.sort }
            OpenedWorkbook(
                workbook = opened.workbook,
                mode = opened.mode,
                chapters = chapters,
                completedByChapterSort = chapters.associate { it.sort to it.hasSelectedAudio() }
            )
        }
}
