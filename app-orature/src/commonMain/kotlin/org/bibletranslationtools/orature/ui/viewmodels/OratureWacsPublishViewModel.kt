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
import org.bibletranslationtools.otter.common.domain.project.OpenWorkbook
import org.bibletranslationtools.otter.common.domain.wacs.usecase.PublishChapterToWacs
import org.bibletranslationtools.otter.common.domain.wacs.usecase.WacsPublishException
import org.jetbrains.compose.resources.getString
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.wacs_publish_error_auth_rejected
import org.bibletranslationtools.shared.resources.wacs_publish_error_conflict
import org.bibletranslationtools.shared.resources.wacs_publish_error_generic
import org.bibletranslationtools.shared.resources.wacs_publish_error_integrity
import org.bibletranslationtools.shared.resources.wacs_publish_error_network
import org.bibletranslationtools.shared.resources.wacs_publish_error_no_audio
import org.bibletranslationtools.shared.resources.wacs_publish_error_no_official_repo
import org.bibletranslationtools.shared.resources.wacs_publish_error_not_authenticated
import org.bibletranslationtools.shared.resources.wacs_publish_no_chapters
import org.bibletranslationtools.shared.resources.wacs_publish_pr_error

/**
 * M5(a): Orature's port of the recorder app's `WacsPublishViewModel` — drives
 * [PublishChapterToWacs] for the chapters the user selected under the per-book Export dialog's
 * "Publish" type (see [org.bibletranslationtools.orature.ui.components.OratureExportProjectDialog]
 * and [org.bibletranslationtools.orature.ui.screens.OratureHomeScreen]'s `onPublishToWacs` hook),
 * threaded through [org.bibletranslationtools.orature.ui.navigation.OratureWacsPublishRoute] as
 * `workbookDescriptorId`/`chapters`. Hosted as a Koin factory parameterized by those nav args (see
 * `OratureViewModelModule.kt`), separate from [OratureWacsLoginViewModel] so this stays scoped to
 * a single navigation into the publish screen rather than the process-lifetime login session.
 *
 * Unlike the recorder app's version (which resolves the workbook from a source/target collection
 * id pair via `IWorkbookRepository`), Orature identifies a project by a single
 * `WorkbookDescriptor.id` everywhere (see [org.bibletranslationtools.orature.ui.viewmodels.OratureExportProjectViewModel]
 * for the same pattern) — so this resolves the workbook with the shared [OpenWorkbook] use case
 * instead.
 *
 * [OratureWacsPublishUiState.progress] carries the raw [PublishChapterToWacs.Progress] rather than
 * an already-localized string — resolving it to text is the Composable's job (`stringResource` is
 * `@Composable`-only, and [PublishChapterToWacs.publish]'s progress callback is a plain,
 * non-suspend `(Progress) -> Unit`).
 *
 * Once every selected chapter has been pushed to the fork, [publish] opens (or reuses) the pull
 * request back to the official repo exactly ONCE for the whole batch — see
 * [PublishChapterToWacs.openContributionPullRequest]'s KDoc for why per-session, not per-chapter,
 * is correct here. That call uses the last chapter's successful [PublishChapterToWacs.Result],
 * since every chapter in a session shares the same fork/branch.
 */
class OratureWacsPublishViewModel(
    private val openWorkbook: OpenWorkbook,
    private val publishChapterToWacs: PublishChapterToWacs,
    private val workbookDescriptorId: Int,
    private val chapterSorts: List<Int>,
) : ViewModel() {

    private val _state = MutableStateFlow(
        OratureWacsPublishUiState(
            chapters = chapterSorts,
            perChapter = chapterSorts.associateWith { ChapterPublishState.Pending }
        )
    )
    val state: StateFlow<OratureWacsPublishUiState> = _state.asStateFlow()

    fun publish() {
        if (_state.value.isPublishing) return
        if (chapterSorts.isEmpty()) {
            viewModelScope.launch {
                _state.update { it.copy(error = getString(Res.string.wacs_publish_no_chapters)) }
            }
            return
        }

        _state.update {
            it.copy(
                isPublishing = true,
                error = null,
                progress = null,
                perChapter = chapterSorts.associateWith { _ -> ChapterPublishState.Pending }
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val workbook = openWorkbook.open(workbookDescriptorId).workbook

                var publishedCount = 0
                var lastResult: PublishChapterToWacs.Result? = null
                for (chapterSort in chapterSorts) {
                    _state.update {
                        it.copy(perChapter = it.perChapter + (chapterSort to ChapterPublishState.InProgress))
                    }
                    try {
                        lastResult = publishChapterToWacs.publish(
                            PublishChapterToWacs.Request(workbook, chapterSort)
                        ) { progress ->
                            _state.update { it.copy(progress = progress) }
                        }
                        publishedCount++
                        _state.update {
                            it.copy(perChapter = it.perChapter + (chapterSort to ChapterPublishState.Success))
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: WacsPublishException) {
                        _state.update {
                            it.copy(perChapter = it.perChapter + (chapterSort to ChapterPublishState.Failed))
                        }
                        // Stop at the first failure — a later chapter succeeding while an earlier
                        // one silently failed would be confusing, and NOT_AUTHENTICATED/AUTH_REJECTED
                        // will only repeat for every remaining chapter anyway.
                        _state.update { it.copy(isPublishing = false, error = localize(e.reason), progress = null) }
                        return@launch
                    }
                }

                // One PR for the whole session, opened (or reused) once every chapter above has
                // pushed — never per chapter. A partial failure already returned above, so reaching
                // here means every selected chapter succeeded and `lastResult` is non-null.
                var pullRequestUrl: String? = null
                var pullRequestError: String? = null
                lastResult?.let { result ->
                    _state.update { it.copy(progress = PublishChapterToWacs.Progress.OpeningPullRequest) }
                    try {
                        pullRequestUrl = publishChapterToWacs.openContributionPullRequest(result).htmlUrl
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: WacsPublishException) {
                        // The chapters themselves are safely published to the fork either way —
                        // report this as a soft warning, not a hard publish failure.
                        pullRequestError = getString(Res.string.wacs_publish_pr_error, localize(e.reason))
                    }
                }

                _state.update {
                    it.copy(
                        isPublishing = false,
                        progress = null,
                        publishedCount = publishedCount,
                        pullRequestUrl = pullRequestUrl,
                        pullRequestError = pullRequestError,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: WacsPublishException) {
                _state.update { it.copy(isPublishing = false, error = localize(e.reason), progress = null) }
            } catch (e: Exception) {
                val message = getString(Res.string.wacs_publish_error_generic)
                _state.update { it.copy(isPublishing = false, error = message, progress = null) }
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }
    fun acknowledgeSuccess() = _state.update {
        it.copy(publishedCount = null, pullRequestUrl = null, pullRequestError = null)
    }

    private suspend fun localize(reason: WacsPublishException.Reason): String = when (reason) {
        WacsPublishException.Reason.NOT_AUTHENTICATED -> getString(Res.string.wacs_publish_error_not_authenticated)
        WacsPublishException.Reason.AUTH_REJECTED -> getString(Res.string.wacs_publish_error_auth_rejected)
        WacsPublishException.Reason.NETWORK -> getString(Res.string.wacs_publish_error_network)
        WacsPublishException.Reason.NO_OFFICIAL_REPO -> getString(Res.string.wacs_publish_error_no_official_repo)
        WacsPublishException.Reason.NO_AUDIO -> getString(Res.string.wacs_publish_error_no_audio)
        WacsPublishException.Reason.CONFLICT -> getString(Res.string.wacs_publish_error_conflict)
        WacsPublishException.Reason.INTEGRITY -> getString(Res.string.wacs_publish_error_integrity)
        WacsPublishException.Reason.UNKNOWN -> getString(Res.string.wacs_publish_error_generic)
    }
}

enum class ChapterPublishState { Pending, InProgress, Success, Failed }

data class OratureWacsPublishUiState(
    val chapters: List<Int> = emptyList(),
    val perChapter: Map<Int, ChapterPublishState> = emptyMap(),
    val isPublishing: Boolean = false,
    val progress: PublishChapterToWacs.Progress? = null,
    val error: String? = null,
    val publishedCount: Int? = null,
    /** The session's pull request URL, once [PublishChapterToWacs.openContributionPullRequest] succeeds. */
    val pullRequestUrl: String? = null,
    /** Set instead of [pullRequestUrl] if chapters published fine but opening the PR failed. */
    val pullRequestError: String? = null,
)
