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
import org.bibletranslationtools.otter.common.domain.wacs.usecase.PublishChaptersToWacs
import org.bibletranslationtools.otter.common.domain.wacs.usecase.WacsPublishException
import org.jetbrains.compose.resources.getString
import org.slf4j.LoggerFactory
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.wacs_publish_error_auth_rejected
import org.bibletranslationtools.shared.resources.wacs_publish_error_conflict
import org.bibletranslationtools.shared.resources.wacs_publish_error_generic
import org.bibletranslationtools.shared.resources.wacs_publish_error_integrity
import org.bibletranslationtools.shared.resources.wacs_publish_error_network
import org.bibletranslationtools.shared.resources.wacs_publish_error_no_audio
import org.bibletranslationtools.shared.resources.wacs_publish_error_pipeline_rejected
import org.bibletranslationtools.shared.resources.wacs_publish_error_pipeline_unavailable
import org.bibletranslationtools.shared.resources.wacs_publish_error_not_authenticated
import org.bibletranslationtools.shared.resources.wacs_publish_no_chapters

/**
 * Orature's port of the recorder app's `WacsPublishViewModel`: drives [PublishChaptersToWacs] for
 * the chapters the user selected under the per-book Export dialog's "Publish" type (see
 * [org.bibletranslationtools.orature.ui.components.OratureExportProjectDialog] and
 * [org.bibletranslationtools.orature.ui.screens.OratureHomeScreen]'s `onPublishToWacs` hook),
 * threaded through [org.bibletranslationtools.orature.ui.navigation.OratureWacsPublishRoute] as
 * `workbookDescriptorId`/`chapters`. Hosted as a Koin factory parameterized by those nav args (see
 * `OratureViewModelModule.kt`), separate from [OratureWacsLoginViewModel] so this stays scoped to
 * a single navigation into the publish screen rather than the process-lifetime login session.
 *
 * Orature identifies a project by a single `WorkbookDescriptor.id` (see
 * [OratureExportProjectViewModel] for the same pattern), so this resolves the workbook with the
 * shared [OpenWorkbook] use case.
 *
 * All the selected chapters go up in one call, each as its own pull request (or into the open pull
 * request that already has it). [OratureWacsPublishUiState.progress] carries the raw
 * [PublishChaptersToWacs.Progress]; resolving it to text is the Composable's job.
 */
class OratureWacsPublishViewModel(
    private val openWorkbook: OpenWorkbook,
    private val publishChaptersToWacs: PublishChaptersToWacs,
    private val workbookDescriptorId: Int,
    private val chapterSorts: List<Int>,
) : ViewModel() {

    private val _state = MutableStateFlow(
        OratureWacsPublishUiState(
            chapters = chapterSorts,
            perChapter = chapterSorts.associateWith { ChapterPublishState.Pending }
        )
    )
    private val logger = LoggerFactory.getLogger(OratureWacsPublishViewModel::class.java)

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
                perChapter = chapterSorts.associateWith { _ -> ChapterPublishState.InProgress }
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val workbook = openWorkbook.open(workbookDescriptorId).workbook
                val result = publishChaptersToWacs.publish(PublishChaptersToWacs.Request(workbook, chapterSorts)) { progress ->
                    _state.update { it.copy(progress = progress) }
                }
                _state.update {
                    it.copy(
                        isPublishing = false,
                        progress = null,
                        perChapter = chapterSorts.associateWith { ChapterPublishState.Success },
                        publishedCount = chapterSorts.size,
                        nothingNew = result.pullRequests.isEmpty(),
                        pullRequests = result.pullRequests,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: WacsPublishException) {
                val message = localize(e)
                _state.update {
                    it.copy(
                        isPublishing = false,
                        error = message,
                        progress = null,
                        perChapter = chapterSorts.associateWith { ChapterPublishState.Failed },
                    )
                }
            } catch (e: Exception) {
                // A WacsPublishException is logged where it's raised; anything else is unexpected.
                logger.error("Publishing chapters {} to WACS failed unexpectedly", chapterSorts, e)
                val message = getString(Res.string.wacs_publish_error_generic)
                _state.update {
                    it.copy(
                        isPublishing = false,
                        error = message,
                        progress = null,
                        perChapter = chapterSorts.associateWith { ChapterPublishState.Failed },
                    )
                }
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }
    fun acknowledgeSuccess() = _state.update {
        it.copy(publishedCount = null, nothingNew = false, pullRequests = emptyList())
    }

    private suspend fun localize(e: WacsPublishException): String = when (e.reason) {
        WacsPublishException.Reason.NOT_AUTHENTICATED -> getString(Res.string.wacs_publish_error_not_authenticated)
        WacsPublishException.Reason.AUTH_REJECTED -> getString(Res.string.wacs_publish_error_auth_rejected)
        WacsPublishException.Reason.NETWORK -> getString(Res.string.wacs_publish_error_network)
        WacsPublishException.Reason.PIPELINE_REJECTED ->
            getString(Res.string.wacs_publish_error_pipeline_rejected, e.detail ?: e.reason.name)
        WacsPublishException.Reason.PIPELINE_UNAVAILABLE -> getString(Res.string.wacs_publish_error_pipeline_unavailable)
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
    val progress: PublishChaptersToWacs.Progress? = null,
    val error: String? = null,
    /** How many chapters the last publish submitted, once it succeeds. */
    val publishedCount: Int? = null,
    /** The last publish found every chapter already in the official repo, so nothing was submitted. */
    val nothingNew: Boolean = false,
    /** The last publish's pull requests, opened or updated, one per chapter. */
    val pullRequests: List<PublishChaptersToWacs.PullRequest> = emptyList(),
)
