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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.WacsLoginViewModel
import org.bibletranslationtools.otter.common.domain.wacs.WacsPlatform
import org.jetbrains.compose.resources.stringResource
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.action_back
import org.bibletranslationtools.shared.resources.wacs_login_host_label
import org.bibletranslationtools.shared.resources.wacs_login_password_hint
import org.bibletranslationtools.shared.resources.wacs_login_password_label
import org.bibletranslationtools.shared.resources.wacs_login_test_connection
import org.bibletranslationtools.shared.resources.wacs_login_title
import org.bibletranslationtools.shared.resources.wacs_login_username_label
import org.bibletranslationtools.shared.resources.wacs_logged_in_as
import org.bibletranslationtools.shared.resources.wacs_logout
import org.bibletranslationtools.shared.resources.wacs_publish_coming_soon
import org.bibletranslationtools.shared.resources.wacs_publish_unsupported_message
import org.bibletranslationtools.shared.resources.wacs_publish_unsupported_title

/**
 * M1 entry point reached from the Export Options dialog's "Publish to WACS" type (see
 * [org.bibletranslationtools.bttrecorder2.ui.components.ExportOptionsDialog]). Three states:
 *
 *   1. Platform unsupported (defense in depth — the dialog already disables the option, but
 *      [JGitWacsGitClient][org.bibletranslationtools.otter.common.domain.wacs.git.JGitWacsGitClient]
 *      guards every op too, so this screen does the same rather than trusting the caller).
 *   2. Not logged in — the M1 login form (host/username/password + "Test connection").
 *   3. Logged in — a stub notice; the actual publish flow (fork → commit → LFS upload → push,
 *      per the plan's M2) is a later increment.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WacsPublishScreen(
    viewModel: WacsLoginViewModel,
    onBackClick: () -> Unit
) {
    val state by viewModel.state.collectAsState()

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
                !WacsPlatform.isGitSyncSupported -> UnsupportedNotice()
                state.isLoggedIn -> LoggedInContent(
                    username = state.loggedInAs.orEmpty(),
                    onLogout = viewModel::logout
                )
                else -> LoginForm(
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
private fun UnsupportedNotice() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(Res.string.wacs_publish_unsupported_title),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = stringResource(Res.string.wacs_publish_unsupported_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun LoggedInContent(username: String, onLogout: () -> Unit) {
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
        Text(
            text = stringResource(Res.string.wacs_publish_coming_soon),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
        )
        OutlinedButton(onClick = onLogout) {
            Text(stringResource(Res.string.wacs_logout))
        }
    }
}

@Composable
private fun LoginForm(
    host: String,
    username: String,
    password: String,
    isLoading: Boolean,
    error: String?,
    onHostChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTestConnection: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = host,
            onValueChange = onHostChange,
            label = { Text(stringResource(Res.string.wacs_login_host_label)) },
            singleLine = true,
            enabled = !isLoading,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            label = { Text(stringResource(Res.string.wacs_login_username_label)) },
            singleLine = true,
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text(stringResource(Res.string.wacs_login_password_label)) },
            singleLine = true,
            enabled = !isLoading,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = stringResource(Res.string.wacs_login_password_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }

        Button(
            onClick = onTestConnection,
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp).padding(end = 8.dp),
                    strokeWidth = 2.dp
                )
            }
            Text(stringResource(Res.string.wacs_login_test_connection))
        }
    }
}
