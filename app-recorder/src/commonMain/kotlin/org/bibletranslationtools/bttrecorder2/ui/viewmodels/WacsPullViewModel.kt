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
package org.bibletranslationtools.bttrecorder2.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookDescriptorRepository
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import org.bibletranslationtools.otter.common.domain.wacs.api.ForgejoRepo
import org.bibletranslationtools.otter.common.domain.wacs.layout.WacsChapterIngredient
import org.bibletranslationtools.otter.common.domain.wacs.usecase.CloneWacsRepo
import org.bibletranslationtools.otter.common.domain.wacs.usecase.ImportPulledChapterAsSource
import org.bibletranslationtools.otter.common.domain.wacs.usecase.ListWacsRepos
import org.bibletranslationtools.otter.common.domain.wacs.usecase.PullChapter
import org.bibletranslationtools.otter.common.domain.wacs.usecase.WacsPullException
import org.jetbrains.compose.resources.getString
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.wacs_pull_attach_error
import org.bibletranslationtools.shared.resources.wacs_pull_attach_success
import org.bibletranslationtools.shared.resources.wacs_pull_error_auth_rejected
import org.bibletranslationtools.shared.resources.wacs_pull_error_chapter_not_found
import org.bibletranslationtools.shared.resources.wacs_pull_error_generic
import org.bibletranslationtools.shared.resources.wacs_pull_error_integrity
import org.bibletranslationtools.shared.resources.wacs_pull_error_network
import org.bibletranslationtools.shared.resources.wacs_pull_error_no_scope
import org.bibletranslationtools.shared.resources.wacs_pull_error_not_authenticated
import org.bibletranslationtools.shared.resources.wacs_pull_error_repo_not_found

/**
 * M3: drives the "pull as source" flow — list the `AudioTranslation` org's repos, open (clone +
 * read scope) the one the user picks, pull a single chapter's audio on demand, and (optionally)
 * attach it as source audio to one of the user's existing local projects via
 * [ImportPulledChapterAsSource] (see that class's KDoc for why this bridge, and the open UX
 * question it flags around project attachment/creation).
 *
 * Kept deliberately separate from [WacsPublishViewModel] (a different direction of the same sync
 * feature) but shares the same login gate: [org.bibletranslationtools.bttrecorder2.ui.screens.WacsPullScreen]
 * reuses [WacsLoginViewModel]/[org.bibletranslationtools.otter.common.domain.wacs.auth.WacsSession]
 * exactly like [org.bibletranslationtools.bttrecorder2.ui.screens.WacsPublishScreen] does.
 */
class WacsPullViewModel(
    private val listWacsRepos: ListWacsRepos,
    private val cloneWacsRepo: CloneWacsRepo,
    private val pullChapter: PullChapter,
    private val importPulledChapterAsSource: ImportPulledChapterAsSource,
    private val workbookDescriptorRepository: IWorkbookDescriptorRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(WacsPullUiState())
    val state: StateFlow<WacsPullUiState> = _state.asStateFlow()

    /** Set by [selectRepo], read by [pullSelectedChapter] — not part of the UI state (carries a [java.io.File]). */
    private var openedRepo: CloneWacsRepo.Result? = null

    /** Set by [pullSelectedChapter], read by [attachToProject] — carries a [java.io.File]. */
    private var pulledFile: java.io.File? = null

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
                selectedBook = null,
                selectedChapter = null,
                pulledChapter = null,
                matchingProjects = emptyList(),
                attachSuccessMessage = null,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val opened = cloneWacsRepo.open(repo.name)
                openedRepo = opened
                val scopeForUi = opened.scope.books.mapValues { (_, chapters) -> chapters.map { it.chapterNumber } }
                _state.update { it.copy(isOpeningRepo = false, scope = scopeForUi) }
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
        pulledFile = null
        _state.update {
            it.copy(
                selectedRepo = null,
                scope = emptyMap(),
                selectedBook = null,
                selectedChapter = null,
                pulledChapter = null,
                matchingProjects = emptyList(),
                attachSuccessMessage = null,
                error = null,
            )
        }
    }

    fun selectChapter(bookSlug: String, chapterNumber: Int) {
        _state.update {
            it.copy(
                selectedBook = bookSlug,
                selectedChapter = chapterNumber,
                pulledChapter = null,
                matchingProjects = emptyList(),
                attachSuccessMessage = null,
            )
        }
    }

    fun pullSelectedChapter() {
        val opened = openedRepo ?: return
        val bookSlug = _state.value.selectedBook ?: return
        val chapterNumber = _state.value.selectedChapter ?: return
        if (_state.value.isPulling) return

        _state.update { it.copy(isPulling = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ingredient: WacsChapterIngredient = opened.scope.books[bookSlug]
                    ?.find { it.chapterNumber == chapterNumber }
                    ?: throw WacsPullException(WacsPullException.Reason.CHAPTER_NOT_FOUND)

                val result = pullChapter.pull(opened.repo, opened.workDir, ingredient)
                pulledFile = result.audioFile

                // Offer local projects whose target book matches this chapter's book — the app
                // has no automatic way to create a project from a WACS source (see
                // ImportPulledChapterAsSource's KDoc), so this only ever lists EXISTING projects.
                val matches = workbookDescriptorRepository.getAllSuspend(computeSourceAudio = false)
                    .filter { it.targetCollection.slug.equals(bookSlug, ignoreCase = true) }

                _state.update {
                    it.copy(isPulling = false, pulledChapter = chapterNumber, matchingProjects = matches)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: WacsPullException) {
                _state.update { it.copy(isPulling = false, error = localize(e.reason)) }
            } catch (e: Exception) {
                _state.update { it.copy(isPulling = false, error = getString(Res.string.wacs_pull_error_generic)) }
            }
        }
    }

    fun attachToProject(descriptor: WorkbookDescriptor) {
        openedRepo ?: return
        val bookSlug = _state.value.selectedBook ?: return
        val chapterNumber = _state.value.selectedChapter ?: return
        val audioFile = pulledFile ?: return
        if (_state.value.isAttaching) return

        _state.update { it.copy(isAttaching = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = withContext(Dispatchers.IO) {
                    importPulledChapterAsSource.import(descriptor, bookSlug, chapterNumber, audioFile)
                }
                if (result.imported.isNotEmpty()) {
                    val message = getString(Res.string.wacs_pull_attach_success, descriptor.title)
                    _state.update { it.copy(isAttaching = false, attachSuccessMessage = message) }
                } else {
                    val detail = (result.errors + result.skipped).firstOrNull().orEmpty()
                    val message = getString(Res.string.wacs_pull_attach_error, detail)
                    _state.update { it.copy(isAttaching = false, error = message) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = getString(
                    Res.string.wacs_pull_attach_error,
                    e.message ?: e::class.simpleName.orEmpty()
                )
                _state.update { it.copy(isAttaching = false, error = message) }
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }
    fun dismissAttachSuccess() = _state.update { it.copy(attachSuccessMessage = null) }

    private suspend fun localize(reason: WacsPullException.Reason): String = when (reason) {
        WacsPullException.Reason.NOT_AUTHENTICATED -> getString(Res.string.wacs_pull_error_not_authenticated)
        WacsPullException.Reason.AUTH_REJECTED -> getString(Res.string.wacs_pull_error_auth_rejected)
        WacsPullException.Reason.NETWORK -> getString(Res.string.wacs_pull_error_network)
        WacsPullException.Reason.REPO_NOT_FOUND -> getString(Res.string.wacs_pull_error_repo_not_found)
        WacsPullException.Reason.NO_SCOPE -> getString(Res.string.wacs_pull_error_no_scope)
        WacsPullException.Reason.CHAPTER_NOT_FOUND -> getString(Res.string.wacs_pull_error_chapter_not_found)
        WacsPullException.Reason.INTEGRITY -> getString(Res.string.wacs_pull_error_integrity)
        WacsPullException.Reason.UNKNOWN -> getString(Res.string.wacs_pull_error_generic)
    }
}

data class WacsPullUiState(
    val isLoadingRepos: Boolean = false,
    val repos: List<ForgejoRepo> = emptyList(),
    val selectedRepo: ForgejoRepo? = null,
    val isOpeningRepo: Boolean = false,
    /** bookSlug -> available chapter numbers, for display only (the ViewModel keeps the full scope). */
    val scope: Map<String, List<Int>> = emptyMap(),
    val selectedBook: String? = null,
    val selectedChapter: Int? = null,
    val isPulling: Boolean = false,
    val pulledChapter: Int? = null,
    val matchingProjects: List<WorkbookDescriptor> = emptyList(),
    val isAttaching: Boolean = false,
    val attachSuccessMessage: String? = null,
    val error: String? = null,
)
