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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.workbook.Workbook

/**
 * Scaffolds a project's on-disk files, so that opening it twice is the same as opening it once.
 *
 * Writes the project resource container (`manifest.yaml`), copies the source files in, and creates
 * the selected-takes, chunks and project-mode files (JVM: `HomePageViewModel2.initializeProjectFiles`,
 * called from `openWorkbook`). Every step is idempotent — each no-ops when its output already
 * exists — which is what makes it safe to run on every open rather than only on create.
 *
 * This used to be a private method on Orature's `OratureWorkbookDataStore`, an app-scoped Compose
 * state holder, wrapped in a `runCatching` whose failure branch printed to stderr. Two things were
 * wrong with that. The filesystem work did not belong in a state holder, and — worse — a failure
 * was invisible: the screen opened, and the missing `manifest.yaml` surfaced much later and
 * somewhere else entirely, as a `ResourceContainer.load(projectDir)` blowing up inside chunking's
 * source-audio copy. Reporting *which* step failed, at the moment it failed, is the point of
 * [Result].
 *
 * Contributor info is deliberately not written here (metadata-only, deferred).
 */
class InitializeProjectFiles(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    /**
     * The steps in the order they run. Later steps assume the resource container exists, so
     * [RESOURCE_CONTAINER] is the one whose failure means the project cannot be opened at all;
     * the rest degrade a working project rather than preventing one.
     */
    enum class Step(val required: Boolean) {
        /** `manifest.yaml` — everything that calls `ResourceContainer.load` depends on it. */
        RESOURCE_CONTAINER(required = true),

        /** The source RC zip. Without it the source text and audio panels have nothing to read. */
        SOURCE_FILES(required = false),

        /** `selected.json`. Recreated empty; take selection is re-derived from the database. */
        SELECTED_TAKES(required = false),

        /** `chunks.json`. Recreated empty; chunking rewrites it. */
        CHUNKS(required = false),

        /** The project-mode marker file, read back when reopening the project. */
        PROJECT_MODE(required = false)
    }

    sealed interface Result {
        data object Success : Result

        /**
         * [step] failed and the steps after it did not run — they assume its output exists.
         *
         * [projectUsable] is false only when a required step failed. A caller should log every
         * failure but should refuse to open the project on that one, rather than letting the user
         * into a screen that will break later for a reason that looks unrelated.
         */
        data class Failed(val step: Step, val cause: Throwable) : Result {
            val projectUsable: Boolean get() = !step.required
        }
    }

    /**
     * Runs the steps in [Step] order on [ioDispatcher], stopping at the first failure.
     *
     * Stopping rather than continuing is deliberate: the later steps write into the resource
     * container directory, so once [Step.RESOURCE_CONTAINER] has failed the rest would either fail
     * in turn or write files nothing can read, and the interesting failure would be buried under
     * four consequential ones.
     */
    suspend fun execute(workbook: Workbook, mode: ProjectMode): Result = withContext(ioDispatcher) {
        val accessor = workbook.projectFilesAccessor
        val steps: List<Pair<Step, () -> Unit>> = listOf(
            Step.RESOURCE_CONTAINER to { accessor.initializeResourceContainerInDir(overwrite = false) },
            Step.SOURCE_FILES to { accessor.copySourceFiles() },
            Step.SELECTED_TAKES to { accessor.createSelectedTakesFile() },
            Step.CHUNKS to { accessor.createChunksFile() },
            Step.PROJECT_MODE to { accessor.setProjectMode(mode) }
        )

        for ((step, run) in steps) {
            try {
                run()
            } catch (e: CancellationException) {
                // Never a scaffolding failure — the caller's screen is going away. Swallowing it
                // here would break cancellation for whoever is waiting on this coroutine.
                throw e
            } catch (e: Exception) {
                return@withContext Result.Failed(step, e)
            }
        }
        Result.Success
    }
}
