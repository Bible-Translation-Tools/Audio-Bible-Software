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
import kotlinx.coroutines.rx2.await
import org.bibletranslationtools.otter.common.api.persistence.repositories.ICollectionRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.ILanguageRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceMetadataRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookDescriptorRepository
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import org.bibletranslationtools.otter.common.domain.collections.CreateProject
import org.bibletranslationtools.otter.common.domain.project.ImportProjectUseCase
import org.bibletranslationtools.otter.common.domain.wacs.api.ForgejoRepo
import org.bibletranslationtools.otter.common.domain.wacs.layout.WacsRepoLayout
import org.bibletranslationtools.otter.common.domain.wacs.layout.WacsRepoScope
import org.bibletranslationtools.otter.common.domain.wacs.layout.WacsScopeReader
import java.io.File
import java.util.Locale

/**
 * M5(d): the device-loss restore case — a user picks a WACS repo to restore but has **no matching
 * local project** (see [RestoreChapterFromWacs]'s KDoc and [WacsPullException.Reason.NO_MATCHING_PROJECT]).
 * Creates that project, then restores every chapter currently in the repo's scope into it via
 * [RestoreChapterFromWacs] — reusing the exact take-insertion path M3.1 already built, unchanged.
 *
 * ### The hard part: where does the source text come from?
 * A WACS repo is an **audio** repo (audio + alignment JSON + `metadata.json` — see the plan doc,
 * §1) — it never carries the source Scripture *text* (USFM/RC) a Recorder/Orature project is
 * created from ([CreateProject.create] always needs a source book [org.bibletranslationtools.otter.common.data.primitives.Collection]).
 * So creating a project from a WACS repo means resolving language + edition + book + [ProjectMode]
 * from the repo alone, then finding (or obtaining) the matching source RC to pair with.
 *
 * **The scope this class actually covers — NARRATION only.** For a NARRATION project (recording
 * audio of scripture text in the *same* language as the text), the repo's own recorded language
 * doubles as the source text's language — there is nothing else to resolve. A DIALECT project
 * (source text in one language, recording in another) cannot be derived this way: the WACS audio
 * repo records only the language that was *spoken*, never which source language it was translated
 * from, so there is no clean derivation for that case. **v1 boundary, explicit and deliberate: this
 * class only ever creates NARRATION projects and never guesses a DIALECT source pairing** — if a
 * repo can't be resolved this way it fails with [WacsPullException.Reason.SOURCE_UNAVAILABLE] rather
 * than fabricating one. (Flagged as a product decision, not made here: whether DIALECT restore
 * should later prompt the user to pick a source language, or stay out of scope indefinitely.)
 *
 * ### Deriving language + book + edition without guessing
 * Three independent, non-overlapping reads make the derivation exact instead of a heuristic parse
 * of one ambiguous string:
 *
 *  1. **Book** — [WacsRepoScope.books]' key(s) (from [WacsScopeReader.read]'s `ingredients{}` scan).
 *     Requires exactly one book; an empty scope (nothing published yet) is [WacsPullException.Reason.NO_SCOPE].
 *  2. **Language** — `metadata.json`'s top-level `languages[].tag` (see
 *     [WacsScopeReader.readLanguageTags]), a Scripture-Burrito-*required* field untouched by the
 *     `type`/`type_specific` schema split [WacsScopeReader] otherwise works around. Requires
 *     exactly one language tag.
 *  3. **Edition** (resource identifier, e.g. `"ulb"`) — [WacsRepoLayout.editionFromRepoName], which
 *     needs (1) and (2) as independent inputs specifically *because* the repo-name convention
 *     (`audio-<lang>-<edition>-<book>`) is ambiguous to parse on its own: several real
 *     gateway-language slugs contain hyphens (`es-419`, `pt-br`, `ar-x-dcv`, ...), so "the second
 *     dash-segment" is not reliably the edition. See that function's KDoc.
 *
 * If any of these doesn't resolve to exactly one answer, this throws rather than picking one
 * arbitrarily — see [WacsPullException.Reason.SOURCE_UNAVAILABLE].
 *
 * ### Obtaining the source RC — bundled-only, no network fetch, no guessing
 * Once language+edition are known, the source RC is resolved in two steps, mirroring exactly what
 * [org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProjectCreationViewModel]'s wizard already
 * does for a human picking a source by hand:
 *
 *  1. Already imported? [IResourceMetadataRepository.getAllSourcesSuspend] filtered by language +
 *     identifier.
 *  2. Not imported, but bundled? [ILanguageRepository.getAvailableGatewaySourcesSuspend] (the
 *     gateway languages whose zip really shipped — see that function's KDoc) contains the
 *     language, so [ImportProjectUseCase.sideloadSource] imports it on demand ([InitializeUlb]'s own
 *     on-demand sibling), then the same lookup runs again.
 *
 * If the sideloaded (or already-imported) source's identifier doesn't actually match the derived
 * edition, or the language isn't bundled at all, this is
 * [WacsPullException.Reason.SOURCE_UNAVAILABLE] — **never** a fallback to a different edition. This
 * is the milestone's explicit v1 boundary: no network fetch of an arbitrary source RC (that would
 * need a whole separate catalog-API integration, out of scope here) and no silent substitution.
 * Verified end-to-end against the prototype's `AudioTranslation/audio-en-ulb-mrk`: `en` is a bundled
 * gateway source whose manifest identifier is exactly `ulb` (see
 * [org.bibletranslationtools.otter.common.initialization.InitializeUlb]'s own `en_ulb.zip`), so this
 * is the "clean path" the milestone asks for, not a hypothetical.
 *
 * ### Project creation + restore
 * Once the source book [org.bibletranslationtools.otter.common.data.primitives.Collection] is in
 * hand, project creation is the EXACT existing machinery
 * ([CreateProject.create], bridged from its `Single<Collection>` via `kotlinx-coroutines-rx2`) —
 * nothing about project creation itself is reinvented here. The resulting
 * [WorkbookDescriptor] is looked up the same way the M3.1 integration test does (there is no
 * "insert and get the row back" API on [IWorkbookDescriptorRepository]), sanity-checked against
 * [WacsRepoLayout.repoNameOrNull] (defense in depth mirroring [RestoreChapterFromWacs.restore]'s own
 * check — this call built the descriptor from the same derivation, so a mismatch here means a bug,
 * not bad input), then every chapter currently in scope is restored via
 * [RestoreChapterFromWacs.restore], one at a time, its own failures downgraded to a per-chapter
 * [Outcome.Failed] rather than aborting the whole restore (mirrors how the Restore UI already
 * treats per-chapter failures for an existing project).
 */
class CreateProjectFromWacs(
    private val collectionRepo: ICollectionRepository,
    private val resourceMetadataRepo: IResourceMetadataRepository,
    private val languageRepo: ILanguageRepository,
    private val createProject: CreateProject,
    private val importProjectUseCase: ImportProjectUseCase,
    private val workbookDescriptorRepository: IWorkbookDescriptorRepository,
    private val restoreChapterFromWacs: RestoreChapterFromWacs,
) {
    /** Per-chapter outcome, mirroring [RestoreChapterFromWacs.Outcome] plus a classified failure. */
    sealed interface ChapterOutcome {
        data class Restored(val take: Take, val selected: Boolean) : ChapterOutcome
        data object AlreadyPresent : ChapterOutcome
        data class Failed(val reason: WacsPullException.Reason) : ChapterOutcome
    }

    data class Result(
        val descriptor: WorkbookDescriptor,
        /** Keyed by chapter number, covering every chapter in the repo's scope at call time. */
        val chapterOutcomes: Map<Int, ChapterOutcome>,
    )

    /**
     * Creates the matching local project for [repo] (already opened via [CloneWacsRepo.open] —
     * [workDir]/[scope] are that call's [CloneWacsRepo.Result.workDir]/`.scope`) and restores every
     * chapter currently in [scope] into it. Throws [WacsPullException] — never a raw JGit/HTTP/DB
     * exception — classifying exactly why per this class's KDoc; a per-chapter restore failure does
     * NOT throw, it is reported in the returned [Result.chapterOutcomes] instead.
     */
    suspend fun create(
        repo: ForgejoRepo,
        workDir: File,
        scope: WacsRepoScope,
        selectionPolicy: RestoreChapterFromWacs.SelectionPolicy = RestoreChapterFromWacs.SelectionPolicy.SELECT_RESTORED,
    ): Result {
        if (scope.books.isEmpty()) throw WacsPullException(WacsPullException.Reason.NO_SCOPE)
        if (scope.books.size > 1) throw WacsPullException(WacsPullException.Reason.SOURCE_UNAVAILABLE)
        val (bookSlug, ingredients) = scope.books.entries.single()
        if (ingredients.isEmpty()) throw WacsPullException(WacsPullException.Reason.NO_SCOPE)

        val metadataFile = File(workDir, WacsRepoLayout.METADATA_PATH)
        val languageTags = try {
            WacsScopeReader.readLanguageTags(metadataFile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw WacsPullException(WacsPullException.Reason.NO_SCOPE, e)
        }
        val languageSlug = languageTags.singleOrNull()
            ?: throw WacsPullException(WacsPullException.Reason.SOURCE_UNAVAILABLE)

        val edition = WacsRepoLayout.editionFromRepoName(repo.name, languageSlug, bookSlug)
            ?: throw WacsPullException(WacsPullException.Reason.SOURCE_UNAVAILABLE)

        val targetLanguage = try {
            languageRepo.getBySlugSuspend(languageSlug)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw WacsPullException(WacsPullException.Reason.SOURCE_UNAVAILABLE, e)
        }

        val sourceMetadata = try {
            resolveSourceMetadata(targetLanguage, edition)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw WacsPullException(WacsPullException.Reason.SOURCE_UNAVAILABLE, e)
        } ?: throw WacsPullException(WacsPullException.Reason.SOURCE_UNAVAILABLE)

        val sourceBook = try {
            val root = collectionRepo.getRootSourcesSuspend()
                .find { it.resourceContainer?.id == sourceMetadata.id }
                ?: throw WacsPullException(WacsPullException.Reason.SOURCE_UNAVAILABLE)
            collectionRepo.getChildrenSuspend(root)
                .find { it.slug == bookSlug.lowercase(Locale.US) }
                ?: throw WacsPullException(WacsPullException.Reason.SOURCE_UNAVAILABLE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: WacsPullException) {
            throw e
        } catch (e: Exception) {
            throw WacsPullException(WacsPullException.Reason.UNKNOWN, e)
        }

        val derivedCollection = try {
            createProject.create(
                sourceProject = sourceBook,
                targetLanguage = targetLanguage,
                mode = ProjectMode.NARRATION,
                resourceId = null,
                deriveProjectFromVerses = true,
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw WacsPullException(WacsPullException.Reason.UNKNOWN, e)
        }

        val descriptor = try {
            workbookDescriptorRepository.getAllSuspend(computeSourceAudio = false)
                .firstOrNull { it.targetCollection.id == derivedCollection.id }
                ?: throw WacsPullException(WacsPullException.Reason.UNKNOWN)
        } catch (e: CancellationException) {
            throw e
        } catch (e: WacsPullException) {
            throw e
        } catch (e: Exception) {
            throw WacsPullException(WacsPullException.Reason.UNKNOWN, e)
        }

        // Defense in depth mirroring RestoreChapterFromWacs.restore's own check: this call derived
        // the descriptor from the same repo, so a mismatch here means a bug in this derivation, not
        // bad caller input.
        if (WacsRepoLayout.repoNameOrNull(descriptor) != repo.name) {
            throw WacsPullException(WacsPullException.Reason.UNKNOWN)
        }

        val outcomes = ingredients.associate { ingredient ->
            val outcome: ChapterOutcome = try {
                when (val restored = restoreChapterFromWacs.restore(descriptor, repo, workDir, ingredient, selectionPolicy)) {
                    is RestoreChapterFromWacs.Outcome.Restored ->
                        ChapterOutcome.Restored(restored.take, restored.selected)
                    RestoreChapterFromWacs.Outcome.AlreadyPresent -> ChapterOutcome.AlreadyPresent
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: WacsPullException) {
                ChapterOutcome.Failed(e.reason)
            } catch (e: Exception) {
                ChapterOutcome.Failed(WacsPullException.Reason.UNKNOWN)
            }
            ingredient.chapterNumber to outcome
        }

        return Result(descriptor, outcomes)
    }

    /**
     * Already-imported source with this exact language+edition, or the bundled gateway source
     * sideloaded on demand — see this class's KDoc. Null (never a guess/fallback) if neither.
     */
    private suspend fun resolveSourceMetadata(targetLanguage: Language, edition: String) =
        matchingSource(targetLanguage, edition) ?: run {
            val embedded = languageRepo.getAvailableGatewaySourcesSuspend()
            if (embedded.none { it.slug == targetLanguage.slug }) return null
            importProjectUseCase.sideloadSource(targetLanguage).await()
            matchingSource(targetLanguage, edition)
        }

    private suspend fun matchingSource(targetLanguage: Language, edition: String) =
        resourceMetadataRepo.getAllSourcesSuspend()
            .firstOrNull { it.language.slug == targetLanguage.slug && it.identifier == edition }
}
