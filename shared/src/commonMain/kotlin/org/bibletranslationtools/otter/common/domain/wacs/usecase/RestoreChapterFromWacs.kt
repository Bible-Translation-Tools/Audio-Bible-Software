/*
 * Copyright (C) 2020-2026 Wycliffe Associates
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
package org.bibletranslationtools.otter.common.domain.wacs.usecase

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.withTimeoutOrNull
import org.bibletranslationtools.kotlinscripturealignment.model.BurritoAudioAlignment
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import org.bibletranslationtools.otter.common.domain.audio.WriteTakeMarkers
import org.bibletranslationtools.otter.common.domain.audio.metadata.BurritoAlignmentMetadata
import org.bibletranslationtools.otter.common.domain.content.SaveAudioAsNewTake
import org.bibletranslationtools.otter.common.domain.wacs.api.ForgejoRepo
import org.bibletranslationtools.otter.common.domain.wacs.git.IWacsGitClient
import org.bibletranslationtools.otter.common.domain.wacs.layout.WacsChapterIngredient
import org.bibletranslationtools.otter.common.domain.wacs.layout.WacsRepoLayout
import org.bibletranslationtools.otter.common.domain.wacs.layout.WacsTakeProvenance
import java.io.File

/**
 * M3.1: the corrected M3 — a WACS repo is pulled to **restore/continue the user's own work**, not to
 * fetch reference source audio (that is a separate, later feature over a public data API). So the
 * materialize step here adds the pulled chapter as a **take** of the matching local project's
 * chapter, reusing every piece of M3's pull infrastructure ([PullChapter], [WacsChapterIngredient],
 * [IWacsGitClient.listLfsPointers]) unchanged — only what happens to the bytes after download is
 * different. [ImportPulledChapterAsSource]/`SourceAudioImporter` (M3's original materialize step)
 * stays in the tree, unwired, for the future public-API reference-audio feature — see its KDoc.
 *
 * ### Mirroring the recorder's own take-creation path
 * Investigating how takes are actually created (required before writing this) found two usable, real
 * precedents rather than one obvious one:
 *
 *  - [SaveAudioAsNewTake] — "register an existing audio file as a new take", already used by both
 *    apps (`PlaybackViewModel.persistEditedFileAsNewTake`, Orature's chapter-review snapshot) for
 *    exactly this shape: an audio file that did not come from the mic. It gets the take number
 *    ([org.bibletranslationtools.otter.common.data.workbook.AssociatedAudio.getNewTakeNumberSuspend]),
 *    the file name/directory ([org.bibletranslationtools.otter.common.domain.content.WorkbookFileNamerBuilder]
 *    + [org.bibletranslationtools.otter.common.domain.resourcecontainer.project.ProjectFilesAccessor.getChapterAudioDir]),
 *    the copy, and the DB insert all from the one already-audited call path — this is the one used
 *    below, with `chunk = null, recordable = chapter` (a whole-chapter take, same shape
 *    `Narration.createChapterTakeWithAudio`/`ChapterTranslationBuilder.getOrCompile` use for a
 *    chapter-level take).
 *  - The lower-level path (`OngoingProjectImporter.insertTake`: build a `data.primitives.Take`
 *    directly, `ITakeRepository.insertForContentSuspend`, manual `content.selectedTake`) exists for
 *    when there is no live [org.bibletranslationtools.otter.common.data.workbook.Workbook] session —
 *    not needed here since [IWorkbookRepository.get] gives us one.
 *
 * ### The checksum/dedup reconciliation (read this before changing it)
 * The M3.1 design doc's plan was "keyed on the LFS `oid`, compared against `take_entity.checksum`".
 * That doesn't hold up: `checksum` is [org.bibletranslationtools.otter.common.utils.computeFileChecksum]'s
 * **MD5**, and it is not a free identity field — it is the take's *checking-status* checksum
 * ([org.bibletranslationtools.otter.common.data.workbook.TakeCheckingState]), compared elsewhere
 * against a fresh MD5 of the file to decide whether a checked take changed since review
 * ([org.bibletranslationtools.otter.common.data.workbook.Chunk], `ChapterTranslationBuilder`).
 * Writing the sha256 oid there would misreport a never-checked restored take as carrying saved
 * checking state, for a value that would never even compare equal (nothing there is ever sha256).
 * Dedup instead uses [WacsTakeProvenance] — a sidecar file next to the take's audio, written once a
 * restore succeeds, read back here before the next restore downloads anything. See that class's KDoc
 * for the full reasoning and why a sidecar (not a schema migration) was chosen.
 *
 * ### Selected-take behavior (a user choice — see [SelectionPolicy])
 * A clean restore (chapter has no existing non-deleted takes) always leaves the restored take
 * selected — it IS the chapter's audio now, and there is no prior selection to preserve. When the
 * chapter already has takes, the caller chooses via [SelectionPolicy]: [SelectionPolicy.SELECT_RESTORED]
 * (default) lets the restored take become selected; [SelectionPolicy.KEEP_CURRENT] preserves the
 * user's current selection and adds the restored take unselected. Only KEEP_CURRENT needs the
 * bounded-wait dance below. The awkward part it works around: [AssociatedAudio.insertTake]
 * (called inside [SaveAudioAsNewTake]) **always** ends with the new take selected, asynchronously,
 * once [org.bibletranslationtools.otter.common.persistence.repositories.WorkbookRepository]'s insert
 * subscription gets an id back — there is no "insert but don't select" flag, and calling
 * `selectTake` yourself right after `insertTake` races that same insert (a real, previously-fixed bug
 * — see the long comment in [SaveAudioAsNewTake]). So instead of fighting that: when there were
 * existing takes, this suspends on [AssociatedAudio.selectedFlow] until the auto-select actually
 * lands on the new take (bounded by a timeout, since the insert is otherwise fire-and-forget from the
 * caller's side), and only then restores the previous selection — which is safe, because by that
 * point the insert has already completed and the take being re-selected is a pre-existing, already-
 * persisted one.
 */
class RestoreChapterFromWacs(
    private val gitClient: IWacsGitClient,
    private val pullChapter: PullChapter,
    private val workbookRepository: IWorkbookRepository,
    private val saveAudioAsNewTake: SaveAudioAsNewTake,
    private val writeTakeMarkers: WriteTakeMarkers,
) {
    sealed interface Outcome {
        /** A new take was inserted. [selected] reflects the effective [SelectionPolicy] (see below). */
        data class Restored(val take: Take, val selected: Boolean) : Outcome

        /** [WacsTakeProvenance] already had this exact `oid` on a non-deleted take — nothing downloaded. */
        data object AlreadyPresent : Outcome
    }

    /**
     * What restoring does to the chapter's selected take **when the chapter already has takes** — a
     * user choice surfaced by the Restore UI (see `WacsPullViewModel`). With no existing takes the
     * restored take is always the selection regardless, since there is nothing to preserve.
     */
    enum class SelectionPolicy {
        /** The restored take becomes the selected take — it is the official published version. Default. */
        SELECT_RESTORED,

        /** Keep whatever take the user currently has selected; add the restored take unselected. */
        KEEP_CURRENT,
    }

    /**
     * Restores [ingredient]'s chapter into [descriptor]'s matching project, or reports it was
     * already restored. [repo]/[workDir] are [CloneWacsRepo.Result.repo]/`.workDir` for the already-
     * opened repo the ingredient came from.
     */
    suspend fun restore(
        descriptor: WorkbookDescriptor,
        repo: ForgejoRepo,
        workDir: File,
        ingredient: WacsChapterIngredient,
        selectionPolicy: SelectionPolicy = SelectionPolicy.SELECT_RESTORED,
    ): Outcome {
        // Defense in depth: the UI is expected to have already matched descriptor <-> repo (see
        // WacsRepoLayout.repoNameOrNull's KDoc) before ever calling restore(), but this is the one
        // place that can actually enforce it - a caller that skipped that check must not silently
        // restore a WACS chapter into the wrong project.
        if (WacsRepoLayout.repoNameOrNull(descriptor) != repo.name) {
            throw WacsPullException(WacsPullException.Reason.NO_MATCHING_PROJECT)
        }

        val tracked = try {
            gitClient.listLfsPointers(workDir)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw classifyGitFailure(e)
        }
        val oid = tracked.find { it.path == ingredient.audioPath }?.pointer?.oid
            ?: throw WacsPullException(WacsPullException.Reason.CHAPTER_NOT_FOUND)

        val workbook = workbookRepository.get(descriptor.sourceCollection, descriptor.targetCollection)
        val chapter = try {
            workbook.target.chapters.toList().await().find { it.sort == ingredient.chapterNumber }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw WacsPullException(WacsPullException.Reason.UNKNOWN, e)
        } ?: throw WacsPullException(WacsPullException.Reason.CHAPTER_NOT_FOUND)

        val existingTakes = chapter.audio.getAllTakes().filterNot { it.isDeleted() }
        if (existingTakes.any { WacsTakeProvenance.read(it.file) == oid }) {
            return Outcome.AlreadyPresent
        }

        val pulled = pullChapter.pull(repo, workDir, ingredient)

        try {
            readMarkers(pulled.companionFiles, pulled.audioFile)
                .takeIf { it.isNotEmpty() }
                ?.let { markers -> writeTakeMarkers.execute(pulled.audioFile, markers, WriteTakeMarkers.ALL_CUE_TYPES) }

            val hadExistingTakes = existingTakes.isNotEmpty()
            val previousSelected = chapter.audio.getSelectedTake()

            val newTake = saveAudioAsNewTake.execute(
                workbook = workbook,
                chapter = chapter,
                chunk = null,
                recordable = chapter,
                audioFile = pulled.audioFile,
            )
            WacsTakeProvenance.write(newTake.file, oid)

            val selected = if (selectionPolicy == SelectionPolicy.KEEP_CURRENT && hadExistingTakes) {
                // The user asked to keep their current selection, and there is one. Wait for
                // WorkbookRepository's own (unsuppressible) auto-select to land on the take we just
                // inserted before touching selection ourselves - see this class's KDoc - then
                // restore the prior selection. This branch (and its bounded wait) runs ONLY on the
                // explicit KEEP_CURRENT choice; SELECT_RESTORED just lets the auto-select stand.
                withTimeoutOrNull(SELECT_TIMEOUT_MS) {
                    chapter.audio.selectedFlow.first { it.value == newTake }
                }
                chapter.audio.selectTake(previousSelected)
                false
            } else {
                true
            }

            return Outcome.Restored(newTake, selected)
        } catch (e: CancellationException) {
            throw e
        } catch (e: WacsPullException) {
            throw e
        } catch (e: Exception) {
            throw WacsPullException(WacsPullException.Reason.UNKNOWN, e)
        }
    }

    /**
     * Best-effort: tries every companion file as a Burrito alignment document (the prototype's seed
     * data and [org.bibletranslationtools.otter.common.domain.wacs.layout.MetadataJsonWriter]'s own
     * writer disagree on the extension - `.timing` vs `.alignment.json` - so this reads by content,
     * not by name, exactly like [org.bibletranslationtools.otter.common.domain.wacs.layout.WacsScopeReader]
     * treats "companion" generically). Resolves the alignment's own docid rather than assuming it
     * matches [audioFile]'s name, because it won't: the docid recorded at publish time is the
     * *original* take's filename, while [audioFile] here is named after the repo's ingredient path
     * (e.g. `01.wav`).
     */
    private fun readMarkers(companionFiles: List<File>, audioFile: File) =
        companionFiles.firstNotNullOfOrNull { companion ->
            runCatching {
                val alignment = BurritoAudioAlignment.load(companion)
                val docid = alignment.getAllDocids().firstOrNull() ?: audioFile.name
                BurritoAlignmentMetadata(companion, audioFile).parseTimings(docid).getMarkers()
            }.getOrNull()
        }.orEmpty()

    private fun classifyGitFailure(e: Exception): WacsPullException {
        val className = e::class.qualifiedName.orEmpty()
        return when {
            e is java.io.IOException || className.contains("Transport") || e.cause is java.io.IOException ->
                WacsPullException(WacsPullException.Reason.NETWORK, e)
            else -> WacsPullException(WacsPullException.Reason.UNKNOWN, e)
        }
    }

    private companion object {
        const val SELECT_TIMEOUT_MS = 10_000L
    }
}
