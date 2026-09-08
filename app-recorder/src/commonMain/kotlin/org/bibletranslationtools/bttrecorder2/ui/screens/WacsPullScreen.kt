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
package org.bibletranslationtools.bttrecorder2.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.clickable
import org.bibletranslationtools.otter.common.domain.wacs.usecase.RestoreChapterFromWacs
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ChapterRestoreResult
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.WacsLoginViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.WacsPullUiState
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.WacsPullViewModel
import org.bibletranslationtools.otter.common.domain.wacs.WacsPlatform
import org.bibletranslationtools.otter.common.domain.wacs.api.ForgejoRepo
import org.jetbrains.compose.resources.stringResource
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.action_back
import org.bibletranslationtools.shared.resources.action_dismiss
import org.bibletranslationtools.shared.resources.wacs_logged_in_as
import org.bibletranslationtools.shared.resources.wacs_logout
import org.bibletranslationtools.shared.resources.wacs_pull_already_present
import org.bibletranslationtools.shared.resources.wacs_pull_back_to_repos
import org.bibletranslationtools.shared.resources.wacs_pull_book_chapters_label
import org.bibletranslationtools.shared.resources.wacs_pull_chapter_button
import org.bibletranslationtools.shared.resources.wacs_pull_chapter_label
import org.bibletranslationtools.shared.resources.wacs_pull_matched_project
import org.bibletranslationtools.shared.resources.wacs_pull_no_matching_project
import org.bibletranslationtools.shared.resources.wacs_pull_no_scope
import org.bibletranslationtools.shared.resources.wacs_pull_opening_repo
import org.bibletranslationtools.shared.resources.wacs_pull_pulling
import org.bibletranslationtools.shared.resources.wacs_pull_repo_select_button
import org.bibletranslationtools.shared.resources.wacs_pull_repos_empty
import org.bibletranslationtools.shared.resources.wacs_pull_repos_loading
import org.bibletranslationtools.shared.resources.wacs_pull_repos_refresh
import org.bibletranslationtools.shared.resources.wacs_pull_restore_failed
import org.bibletranslationtools.shared.resources.wacs_pull_scope_title
import org.bibletranslationtools.shared.resources.wacs_pull_selection_policy_label
import org.bibletranslationtools.shared.resources.wacs_pull_selection_select_restored
import org.bibletranslationtools.shared.resources.wacs_pull_selection_keep_current
import org.bibletranslationtools.shared.resources.wacs_pull_select_repo_title
import org.bibletranslationtools.shared.resources.wacs_pull_success
import org.bibletranslationtools.shared.resources.wacs_pull_title
import org.bibletranslationtools.shared.resources.wacs_pull_unsupported_message
import org.bibletranslationtools.shared.resources.wacs_pull_unsupported_title

/**
 * M3.1 entry point reached from Project Management's overflow menu ("Restore from WACS" — see
 * [ProjectManagementScreen]). Mirrors [WacsPublishScreen]'s three-state shape (unsupported / login /
 * logged-in), then adds its own repo -> scope+project -> restore flow:
 *
 *   1. Not logged in — the shared M1 login form ([WacsLoginForm]).
 *   2. Logged in, no repo chosen yet — the `AudioTranslation` org's repo list.
 *   3. A repo opened — its available chapters (read from `metadata.json`, no audio downloaded yet)
 *      alongside whichever local project matches this repo, if any (v1 restores into an EXISTING
 *      project only — see [org.bibletranslationtools.otter.common.domain.wacs.usecase.RestoreChapterFromWacs]'s
 *      KDoc). Pressing "Restore" on a chapter downloads + verifies it and adds it as a take of that
 *      project directly — there is no separate "attach" step anymore.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WacsPullScreen(
    loginViewModel: WacsLoginViewModel,
    pullViewModel: WacsPullViewModel,
    onBackClick: () -> Unit
) {
    val loginState by loginViewModel.state.collectAsState()
    val pullState by pullViewModel.state.collectAsState()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(Res.string.wacs_pull_title)) },
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
                    title = stringResource(Res.string.wacs_pull_unsupported_title),
                    message = stringResource(Res.string.wacs_pull_unsupported_message)
                )
                !loginState.isLoggedIn -> WacsLoginForm(
                    host = loginState.host,
                    username = loginState.username,
                    password = loginState.password,
                    isLoading = loginState.isLoading,
                    error = loginState.error,
                    onHostChange = loginViewModel::setHost,
                    onUsernameChange = loginViewModel::setUsername,
                    onPasswordChange = loginViewModel::setPassword,
                    onTestConnection = loginViewModel::testConnection
                )
                else -> LoggedInContent(
                    username = loginState.loggedInAs.orEmpty(),
                    onLogout = loginViewModel::logout,
                    state = pullState,
                    viewModel = pullViewModel,
                )
            }
        }
    }
}

@Composable
private fun LoggedInContent(
    username: String,
    onLogout: () -> Unit,
    state: WacsPullUiState,
    viewModel: WacsPullViewModel,
) {
    LaunchedEffect(Unit) {
        if (state.repos.isEmpty() && !state.isLoadingRepos) viewModel.loadRepos()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(Res.string.wacs_logged_in_as, username),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(16.dp))

        state.error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            TextButton(onClick = viewModel::dismissError) { Text(stringResource(Res.string.action_dismiss)) }
            Spacer(modifier = Modifier.height(8.dp))
        }

        when {
            state.selectedRepo == null -> RepoListSection(
                state = state,
                onSelectRepo = viewModel::selectRepo,
                onRefresh = viewModel::loadRepos,
            )
            else -> RepoScopeSection(state = state, viewModel = viewModel)
        }

        Spacer(modifier = Modifier.height(24.dp))
        OutlinedButton(onClick = onLogout) { Text(stringResource(Res.string.wacs_logout)) }
    }
}

@Composable
private fun RepoListSection(
    state: WacsPullUiState,
    onSelectRepo: (ForgejoRepo) -> Unit,
    onRefresh: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(Res.string.wacs_pull_select_repo_title), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onRefresh, enabled = !state.isLoadingRepos) {
                Text(stringResource(Res.string.wacs_pull_repos_refresh))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        when {
            state.isLoadingRepos -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(Res.string.wacs_pull_repos_loading),
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            state.repos.isEmpty() -> Text(
                text = stringResource(Res.string.wacs_pull_repos_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                state.repos.forEach { repo ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(repo.name, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { onSelectRepo(repo) }) {
                            Text(stringResource(Res.string.wacs_pull_repo_select_button))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RepoScopeSection(
    state: WacsPullUiState,
    viewModel: WacsPullViewModel,
) {
    val repo = state.selectedRepo ?: return
    Column(modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = viewModel::backToRepoList) {
            Text(stringResource(Res.string.wacs_pull_back_to_repos))
        }
        Text(
            text = stringResource(Res.string.wacs_pull_scope_title, repo.name),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))

        when {
            state.isOpeningRepo -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(Res.string.wacs_pull_opening_repo),
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            else -> {
                if (state.projectResolved) {
                    val matched = state.matchedProject
                    if (matched != null) {
                        Text(
                            text = stringResource(Res.string.wacs_pull_matched_project, matched.title),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        SelectionPolicyChooser(
                            selected = state.selectionPolicy,
                            onSelect = viewModel::setSelectionPolicy,
                        )
                    } else {
                        Text(
                            text = stringResource(Res.string.wacs_pull_no_matching_project),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (state.scope.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.wacs_pull_no_scope),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.scope.forEach { (bookSlug, chapters) ->
                            Text(
                                text = stringResource(Res.string.wacs_pull_book_chapters_label, bookSlug, chapters.size),
                                style = MaterialTheme.typography.labelLarge
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                chapters.forEach { chapterNumber ->
                                    ChapterRow(
                                        bookSlug = bookSlug,
                                        chapterNumber = chapterNumber,
                                        state = state,
                                        viewModel = viewModel,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Lets the user choose what restoring does to the selected take when a chapter already has takes
 * (see [RestoreChapterFromWacs.SelectionPolicy]). Shown only once a matching local project is
 * resolved, since restore only targets an existing project.
 */
@Composable
private fun SelectionPolicyChooser(
    selected: RestoreChapterFromWacs.SelectionPolicy,
    onSelect: (RestoreChapterFromWacs.SelectionPolicy) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(Res.string.wacs_pull_selection_policy_label),
            style = MaterialTheme.typography.labelLarge
        )
        SelectionPolicyOption(
            text = stringResource(Res.string.wacs_pull_selection_select_restored),
            selected = selected == RestoreChapterFromWacs.SelectionPolicy.SELECT_RESTORED,
            onClick = { onSelect(RestoreChapterFromWacs.SelectionPolicy.SELECT_RESTORED) },
        )
        SelectionPolicyOption(
            text = stringResource(Res.string.wacs_pull_selection_keep_current),
            selected = selected == RestoreChapterFromWacs.SelectionPolicy.KEEP_CURRENT,
            onClick = { onSelect(RestoreChapterFromWacs.SelectionPolicy.KEEP_CURRENT) },
        )
    }
}

@Composable
private fun SelectionPolicyOption(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ChapterRow(
    bookSlug: String,
    chapterNumber: Int,
    state: WacsPullUiState,
    viewModel: WacsPullViewModel,
) {
    val key = bookSlug to chapterNumber
    val isRestoring = state.restoringChapter == key
    val result = state.chapterResults[key]
    val canRestore = state.matchedProject != null

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(Res.string.wacs_pull_chapter_label, chapterNumber),
                style = MaterialTheme.typography.bodyMedium
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isRestoring) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp).padding(end = 8.dp),
                        strokeWidth = 2.dp
                    )
                }
                Button(
                    onClick = { viewModel.restoreChapter(bookSlug, chapterNumber) },
                    enabled = canRestore && state.restoringChapter == null
                ) {
                    Text(stringResource(Res.string.wacs_pull_chapter_button))
                }
            }
        }

        if (isRestoring) {
            Text(
                text = stringResource(Res.string.wacs_pull_pulling, chapterNumber),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        when (result) {
            is ChapterRestoreResult.Restored -> Text(
                text = stringResource(Res.string.wacs_pull_success, chapterNumber),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
            is ChapterRestoreResult.AlreadyPresent -> Text(
                text = stringResource(Res.string.wacs_pull_already_present, chapterNumber),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            is ChapterRestoreResult.Failed -> Text(
                text = stringResource(Res.string.wacs_pull_restore_failed, chapterNumber, result.message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
            null -> Unit
        }
    }
}
