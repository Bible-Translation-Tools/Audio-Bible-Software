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
package org.bibletranslationtools.orature.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.bibletranslationtools.orature.ui.viewmodels.ChapterPublishState
import org.bibletranslationtools.orature.ui.viewmodels.OratureWacsLoginViewModel
import org.bibletranslationtools.orature.ui.viewmodels.OratureWacsPublishUiState
import org.bibletranslationtools.orature.ui.viewmodels.OratureWacsPublishViewModel
import org.bibletranslationtools.otter.common.domain.wacs.WacsPlatform
import org.bibletranslationtools.otter.common.domain.wacs.usecase.PublishChapterToWacs
import org.jetbrains.compose.resources.stringResource
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.action_back
import org.bibletranslationtools.shared.resources.action_dismiss
import org.bibletranslationtools.shared.resources.wacs_login_title
import org.bibletranslationtools.shared.resources.wacs_logged_in_as
import org.bibletranslationtools.shared.resources.wacs_logout
import org.bibletranslationtools.shared.resources.wacs_publish_button
import org.bibletranslationtools.shared.resources.wacs_publish_chapter_label
import org.bibletranslationtools.shared.resources.wacs_publish_chapters_label
import org.bibletranslationtools.shared.resources.wacs_publish_no_chapters
import org.bibletranslationtools.shared.resources.wacs_publish_progress_checking_repo
import org.bibletranslationtools.shared.resources.wacs_publish_progress_cloning
import org.bibletranslationtools.shared.resources.wacs_publish_progress_committing
import org.bibletranslationtools.shared.resources.wacs_publish_progress_forking
import org.bibletranslationtools.shared.resources.wacs_publish_progress_opening_pr
import org.bibletranslationtools.shared.resources.wacs_publish_progress_preparing
import org.bibletranslationtools.shared.resources.wacs_publish_progress_pushing
import org.bibletranslationtools.shared.resources.wacs_publish_progress_syncing
import org.bibletranslationtools.shared.resources.wacs_publish_progress_uploading
import org.bibletranslationtools.shared.resources.wacs_publish_pr_submitted_message
import org.bibletranslationtools.shared.resources.wacs_publish_pr_submitted_title
import org.bibletranslationtools.shared.resources.wacs_publish_status_failed
import org.bibletranslationtools.shared.resources.wacs_publish_status_in_progress
import org.bibletranslationtools.shared.resources.wacs_publish_status_pending
import org.bibletranslationtools.shared.resources.wacs_publish_status_success
import org.bibletranslationtools.shared.resources.wacs_publish_success
import org.bibletranslationtools.shared.resources.wacs_publish_unsupported_message
import org.bibletranslationtools.shared.resources.wacs_publish_unsupported_title

/**
 * M5(a): Orature's port of the recorder app's `WacsPublishScreen`. Entry point reached from the
 * per-book Export dialog's "Publish" type (see
 * [org.bibletranslationtools.orature.ui.components.OratureExportProjectDialog] and
 * [org.bibletranslationtools.orature.ui.screens.OratureHomeScreen]'s `onPublishToWacs` hook, wired
 * in [org.bibletranslationtools.orature.ui.navigation.OratureNavigation] as
 * [org.bibletranslationtools.orature.ui.navigation.OratureWacsPublishRoute]). Three states:
 *
 *   1. Platform unsupported (defense in depth — the export dialog already disables the Publish
 *      option, but [JGitWacsGitClient][org.bibletranslationtools.otter.common.domain.wacs.git.JGitWacsGitClient]
 *      guards every op too, so this screen does the same rather than trusting the caller).
 *   2. Not logged in — the M1 login form (host/username/password + "Test connection").
 *   3. Logged in — the publish action for the chapters
 *      [org.bibletranslationtools.orature.ui.navigation.OratureWacsPublishRoute] carried in:
 *      fork → clone → commit → LFS upload → push, per chapter, driven by [OratureWacsPublishViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OratureWacsPublishScreen(
    viewModel: OratureWacsLoginViewModel,
    publishViewModel: OratureWacsPublishViewModel,
    onBackClick: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val publishState by publishViewModel.state.collectAsState()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(Res.string.wacs_login_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.action_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            when {
                !WacsPlatform.isGitSyncSupported -> WacsUnsupportedNotice(
                    title = stringResource(Res.string.wacs_publish_unsupported_title),
                    message = stringResource(Res.string.wacs_publish_unsupported_message)
                )
                state.isLoggedIn -> LoggedInContent(
                    username = state.loggedInAs.orEmpty(),
                    onLogout = viewModel::logout,
                    publishState = publishState,
                    onPublish = publishViewModel::publish,
                    onDismissError = publishViewModel::dismissError,
                    onAcknowledgeSuccess = publishViewModel::acknowledgeSuccess
                )
                else -> WacsLoginForm(
                    host = state.host,
                    username = state.username,
                    password = state.password,
                    isLoading = state.isLoading,
                    error = state.error,
                    onHostChange = viewModel::setHost,
                    onUsernameChange = viewModel::setUsername,
                    onPasswordChange = viewModel::setPassword,
                    onTestConnection = viewModel::testConnection
                )
            }
        }
    }
}

@Composable
private fun LoggedInContent(
    username: String,
    onLogout: () -> Unit,
    publishState: OratureWacsPublishUiState,
    onPublish: () -> Unit,
    onDismissError: () -> Unit,
    onAcknowledgeSuccess: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            text = stringResource(Res.string.wacs_logged_in_as, username),
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (publishState.chapters.isEmpty()) {
            Text(
                text = stringResource(Res.string.wacs_publish_no_chapters),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                text = stringResource(Res.string.wacs_publish_chapters_label, publishState.chapters.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                publishState.chapters.forEach { chapterSort ->
                    ChapterPublishRow(
                        chapterSort = chapterSort,
                        chapterState = publishState.perChapter[chapterSort] ?: ChapterPublishState.Pending
                    )
                }
            }

            publishState.progress?.let { progress ->
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        text = progressLabel(progress),
                        modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            publishState.error?.let { message ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                TextButton(onClick = onDismissError) { Text(stringResource(Res.string.action_dismiss)) }
            }

            publishState.publishedCount?.let { count ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(Res.string.wacs_publish_success, count),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                // The session's pull request back to the official repo — one per session (see
                // OratureWacsPublishViewModel's KDoc), shown as selectable/copyable text since
                // desktop has no in-app browser to launch it from.
                publishState.pullRequestUrl?.let { url ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(Res.string.wacs_publish_pr_submitted_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(Res.string.wacs_publish_pr_submitted_message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SelectionContainer {
                        Text(
                            text = url,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                publishState.pullRequestError?.let { message ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                TextButton(onClick = onAcknowledgeSuccess) { Text(stringResource(Res.string.action_dismiss)) }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onPublish,
                enabled = !publishState.isPublishing,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (publishState.isPublishing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp).padding(end = 8.dp),
                        strokeWidth = 2.dp
                    )
                }
                Text(stringResource(Res.string.wacs_publish_button))
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        OutlinedButton(onClick = onLogout) {
            Text(stringResource(Res.string.wacs_logout))
        }
    }
}

@Composable
private fun ChapterPublishRow(chapterSort: Int, chapterState: ChapterPublishState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(Res.string.wacs_publish_chapter_label, chapterSort),
            style = MaterialTheme.typography.bodyMedium
        )
        val (label, color) = when (chapterState) {
            ChapterPublishState.Pending -> stringResource(Res.string.wacs_publish_status_pending) to MaterialTheme.colorScheme.onSurfaceVariant
            ChapterPublishState.InProgress -> stringResource(Res.string.wacs_publish_status_in_progress) to MaterialTheme.colorScheme.primary
            ChapterPublishState.Success -> stringResource(Res.string.wacs_publish_status_success) to MaterialTheme.colorScheme.primary
            ChapterPublishState.Failed -> stringResource(Res.string.wacs_publish_status_failed) to MaterialTheme.colorScheme.error
        }
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
private fun progressLabel(progress: PublishChapterToWacs.Progress): String = when (progress) {
    is PublishChapterToWacs.Progress.CheckingOfficialRepo -> stringResource(Res.string.wacs_publish_progress_checking_repo)
    is PublishChapterToWacs.Progress.Forking -> stringResource(Res.string.wacs_publish_progress_forking)
    is PublishChapterToWacs.Progress.Cloning -> stringResource(Res.string.wacs_publish_progress_cloning)
    is PublishChapterToWacs.Progress.SyncingUpstream -> stringResource(Res.string.wacs_publish_progress_syncing)
    is PublishChapterToWacs.Progress.PreparingFiles -> stringResource(Res.string.wacs_publish_progress_preparing)
    is PublishChapterToWacs.Progress.Committing -> stringResource(Res.string.wacs_publish_progress_committing)
    is PublishChapterToWacs.Progress.UploadingAudio -> stringResource(Res.string.wacs_publish_progress_uploading)
    is PublishChapterToWacs.Progress.Pushing -> stringResource(Res.string.wacs_publish_progress_pushing)
    is PublishChapterToWacs.Progress.OpeningPullRequest -> stringResource(Res.string.wacs_publish_progress_opening_pr)
    is PublishChapterToWacs.Progress.Done -> ""
}
