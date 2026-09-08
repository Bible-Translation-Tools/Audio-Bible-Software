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
package org.bibletranslationtools.orature.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookDescriptorRepository
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import org.bibletranslationtools.otter.common.domain.wacs.api.ForgejoRepo
import org.bibletranslationtools.otter.common.domain.wacs.layout.WacsChapterIngredient
import org.bibletranslationtools.otter.common.domain.wacs.layout.WacsRepoLayout
import org.bibletranslationtools.otter.common.domain.wacs.usecase.CloneWacsRepo
import org.bibletranslationtools.otter.common.domain.wacs.usecase.ListWacsRepos
import org.bibletranslationtools.otter.common.domain.wacs.usecase.RestoreChapterFromWacs
import org.bibletranslationtools.otter.common.domain.wacs.usecase.WacsPullException
import org.jetbrains.compose.resources.getString
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.wacs_pull_error_auth_rejected
import org.bibletranslationtools.shared.resources.wacs_pull_error_chapter_not_found
import org.bibletranslationtools.shared.resources.wacs_pull_error_generic
import org.bibletranslationtools.shared.resources.wacs_pull_error_integrity
import org.bibletranslationtools.shared.resources.wacs_pull_error_network
import org.bibletranslationtools.shared.resources.wacs_pull_error_no_matching_project
import org.bibletranslationtools.shared.resources.wacs_pull_error_no_scope
import org.bibletranslationtools.shared.resources.wacs_pull_error_not_authenticated
import org.bibletranslationtools.shared.resources.wacs_pull_error_repo_not_found

/**
 * M5(a): Orature's port of the recorder app's `WacsPullViewModel` — drives the "Restore from
 * WACS" flow: list the `AudioTranslation` org's repos, open (clone + read scope) the one the user
 * picks, resolve which (if any) local project matches that repo, and restore chapters into it one
 * at a time as new **takes** via [RestoreChapterFromWacs] — see that class's KDoc for the
 * take-model/checksum reconciliation this was built against.
 *
 * v1 restores into an EXISTING matching project only ([WacsRepoLayout.repoNameOrNull] vs each local
 * project's own computed repo name) — there is deliberately no "create a project from this repo"
 * path here (needs a source *text* to pair with, which this milestone does not resolve).
 *
 * Kept deliberately separate from [OratureWacsPublishViewModel] (a different direction of the same
 * sync feature) but shares the same login gate: [org.bibletranslationtools.orature.ui.screens.OratureWacsPullScreen]
 * reuses [OratureWacsLoginViewModel]/[org.bibletranslationtools.otter.common.domain.wacs.auth.WacsSession]
 * exactly like [org.bibletranslationtools.orature.ui.screens.OratureWacsPublishScreen] does.
 */
class OratureWacsPullViewModel(
    private val listWacsRepos: ListWacsRepos,
    private val cloneWacsRepo: CloneWacsRepo,
    private val restoreChapterFromWacs: RestoreChapterFromWacs,
    private val workbookDescriptorRepository: IWorkbookDescriptorRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(OratureWacsPullUiState())
    val state: StateFlow<OratureWacsPullUiState> = _state.asStateFlow()

    /** Set by [selectRepo], read by [restoreChapter] — not part of the UI state (carries a [java.io.File]). */
    private var openedRepo: CloneWacsRepo.Result? = null

    fun loadRepos() {
        if (_state.value.isLoadingRepos) return
        _state.update { it.copy(isLoadingRepos = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val repos = listWacsRepos.list()
                _state.update { it.copy(isLoadingRepos = false, repos = repos) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: WacsPullException) {
                _state.update { it.copy(isLoadingRepos = false, error = localize(e.reason)) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoadingRepos = false, error = getString(Res.string.wacs_pull_error_generic)) }
            }
        }
    }

    fun selectRepo(repo: ForgejoRepo) {
        if (_state.value.isOpeningRepo) return
        _state.update {
            it.copy(
                selectedRepo = repo,
                isOpeningRepo = true,
                error = null,
                scope = emptyMap(),
                matchedProject = null,
                projectResolved = false,
                chapterResults = emptyMap(),
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val opened = cloneWacsRepo.open(repo.name)
                openedRepo = opened
                val scopeForUi = opened.scope.books.mapValues { (_, chapters) -> chapters.map { it.chapterNumber } }

                // v1: restore into an existing matching project only — no project creation here.
                val matched = workbookDescriptorRepository.getAllSuspend(computeSourceAudio = false)
                    .firstOrNull { WacsRepoLayout.repoNameOrNull(it) == repo.name }

                _state.update {
                    it.copy(
                        isOpeningRepo = false,
                        scope = scopeForUi,
                        matchedProject = matched,
                        projectResolved = true,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: WacsPullException) {
                _state.update { it.copy(isOpeningRepo = false, error = localize(e.reason)) }
            } catch (e: Exception) {
                _state.update { it.copy(isOpeningRepo = false, error = getString(Res.string.wacs_pull_error_generic)) }
            }
        }
    }

    fun backToRepoList() {
        openedRepo = null
        _state.update {
            it.copy(
                selectedRepo = null,
                scope = emptyMap(),
                matchedProject = null,
                projectResolved = false,
                restoringChapter = null,
                chapterResults = emptyMap(),
                error = null,
            )
        }
    }

    fun restoreChapter(bookSlug: String, chapterNumber: Int) {
        val opened = openedRepo ?: return
        val descriptor = _state.value.matchedProject ?: return
        val key = bookSlug to chapterNumber
        if (_state.value.restoringChapter != null) return

        _state.update {
            it.copy(
                restoringChapter = key,
                error = null,
                chapterResults = it.chapterResults - key,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ingredient: WacsChapterIngredient = opened.scope.books[bookSlug]
                    ?.find { it.chapterNumber == chapterNumber }
                    ?: throw WacsPullException(WacsPullException.Reason.CHAPTER_NOT_FOUND)

                val outcome = restoreChapterFromWacs.restore(
                    descriptor, opened.repo, opened.workDir, ingredient, _state.value.selectionPolicy,
                )
                val result = when (outcome) {
                    is RestoreChapterFromWacs.Outcome.Restored -> ChapterRestoreResult.Restored
                    RestoreChapterFromWacs.Outcome.AlreadyPresent -> ChapterRestoreResult.AlreadyPresent
                }
                _state.update {
                    it.copy(restoringChapter = null, chapterResults = it.chapterResults + (key to result))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: WacsPullException) {
                val message = localize(e.reason)
                _state.update {
                    it.copy(
                        restoringChapter = null,
                        chapterResults = it.chapterResults + (key to ChapterRestoreResult.Failed(message)),
                    )
                }
            } catch (e: Exception) {
                val message = getString(Res.string.wacs_pull_error_generic)
                _state.update {
                    it.copy(
                        restoringChapter = null,
                        chapterResults = it.chapterResults + (key to ChapterRestoreResult.Failed(message)),
                    )
                }
            }
        }
    }

    /** How restoring should treat the selected take when a chapter already has takes (see [RestoreChapterFromWacs.SelectionPolicy]). */
    fun setSelectionPolicy(policy: RestoreChapterFromWacs.SelectionPolicy) =
        _state.update { it.copy(selectionPolicy = policy) }

    fun dismissError() = _state.update { it.copy(error = null) }

    private suspend fun localize(reason: WacsPullException.Reason): String = when (reason) {
        WacsPullException.Reason.NOT_AUTHENTICATED -> getString(Res.string.wacs_pull_error_not_authenticated)
        WacsPullException.Reason.AUTH_REJECTED -> getString(Res.string.wacs_pull_error_auth_rejected)
        WacsPullException.Reason.NETWORK -> getString(Res.string.wacs_pull_error_network)
        WacsPullException.Reason.REPO_NOT_FOUND -> getString(Res.string.wacs_pull_error_repo_not_found)
        WacsPullException.Reason.NO_SCOPE -> getString(Res.string.wacs_pull_error_no_scope)
        WacsPullException.Reason.CHAPTER_NOT_FOUND -> getString(Res.string.wacs_pull_error_chapter_not_found)
        WacsPullException.Reason.INTEGRITY -> getString(Res.string.wacs_pull_error_integrity)
        WacsPullException.Reason.NO_MATCHING_PROJECT -> getString(Res.string.wacs_pull_error_no_matching_project)
        WacsPullException.Reason.UNKNOWN -> getString(Res.string.wacs_pull_error_generic)
    }
}

/** Per-chapter outcome of a restore attempt, keyed by (bookSlug, chapterNumber) in the UI state. */
sealed interface ChapterRestoreResult {
    data object Restored : ChapterRestoreResult
    data object AlreadyPresent : ChapterRestoreResult
    data class Failed(val message: String) : ChapterRestoreResult
}

data class OratureWacsPullUiState(
    val isLoadingRepos: Boolean = false,
    val repos: List<ForgejoRepo> = emptyList(),
    val selectedRepo: ForgejoRepo? = null,
    val isOpeningRepo: Boolean = false,
    /** bookSlug -> available chapter numbers, for display only (the ViewModel keeps the full scope). */
    val scope: Map<String, List<Int>> = emptyMap(),
    /** True once repo-opening has finished trying to resolve a matching project (whether found or not). */
    val projectResolved: Boolean = false,
    /** The local project this repo restores into, or null if none matches (see [projectResolved]). */
    val matchedProject: WorkbookDescriptor? = null,
    val restoringChapter: Pair<String, Int>? = null,
    val chapterResults: Map<Pair<String, Int>, ChapterRestoreResult> = emptyMap(),
    /** User choice: what restoring does to the selected take when a chapter already has takes. */
    val selectionPolicy: RestoreChapterFromWacs.SelectionPolicy =
        RestoreChapterFromWacs.SelectionPolicy.SELECT_RESTORED,
    val error: String? = null,
)
