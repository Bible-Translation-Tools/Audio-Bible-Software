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
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
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

/**
 * M2: drives [PublishChapterToWacs] for the chapters the user selected under Export Options'
 * "Publish to WACS" type (see [org.bibletranslationtools.bttrecorder2.ui.screens.ProjectManagementScreen]),
 * threaded through [org.bibletranslationtools.bttrecorder2.ui.navigation.WacsPublishRoute] as
 * `sourceId`/`targetId`/`chapters`. Hosted as a Koin factory parameterized by those nav args (see
 * `RecorderKoinModules.kt`), separate from [WacsLoginViewModel] so this stays scoped to a single
 * navigation into the publish screen rather than the process-lifetime login session.
 *
 * [WacsPublishUiState.progress] carries the raw [PublishChapterToWacs.Progress] rather than an
 * already-localized string — resolving it to text is the Composable's job (`stringResource` is
 * `@Composable`-only, and [PublishChapterToWacs.publish]'s progress callback is a plain,
 * non-suspend `(Progress) -> Unit`).
 */
class WacsPublishViewModel(
    private val workbookRepository: IWorkbookRepository,
    private val publishChapterToWacs: PublishChapterToWacs,
    private val sourceId: Int,
    private val targetId: Int,
    private val chapterSorts: List<Int>,
) : ViewModel() {

    private val _state = MutableStateFlow(
        WacsPublishUiState(
            chapters = chapterSorts,
            perChapter = chapterSorts.associateWith { ChapterPublishState.Pending }
        )
    )
    val state: StateFlow<WacsPublishUiState> = _state.asStateFlow()

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
                val workbook = workbookRepository.getProjectsSuspend().find {
                    it.source.collectionId == sourceId && it.target.collectionId == targetId
                } ?: throw WacsPublishException(WacsPublishException.Reason.UNKNOWN)

                var publishedCount = 0
                for (chapterSort in chapterSorts) {
                    _state.update {
                        it.copy(perChapter = it.perChapter + (chapterSort to ChapterPublishState.InProgress))
                    }
                    try {
                        publishChapterToWacs.publish(
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

                _state.update {
                    it.copy(isPublishing = false, progress = null, publishedCount = publishedCount)
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
    fun acknowledgeSuccess() = _state.update { it.copy(publishedCount = null) }

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

data class WacsPublishUiState(
    val chapters: List<Int> = emptyList(),
    val perChapter: Map<Int, ChapterPublishState> = emptyMap(),
    val isPublishing: Boolean = false,
    val progress: PublishChapterToWacs.Progress? = null,
    val error: String? = null,
    val publishedCount: Int? = null,
)
