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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.bibletranslationtools.otter.common.domain.wacs.WacsConfig
import org.bibletranslationtools.otter.common.domain.wacs.auth.IWacsAuthenticator
import org.bibletranslationtools.otter.common.domain.wacs.auth.WacsAuthException
import org.bibletranslationtools.otter.common.domain.wacs.auth.WacsCredential
import org.bibletranslationtools.otter.common.domain.wacs.auth.WacsSession
import org.jetbrains.compose.resources.getString
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.wacs_login_error_generic
import org.bibletranslationtools.shared.resources.wacs_login_error_invalid_credentials
import org.bibletranslationtools.shared.resources.wacs_login_error_missing_fields
import org.bibletranslationtools.shared.resources.wacs_login_error_server
import org.bibletranslationtools.shared.resources.wacs_login_error_unreachable

/**
 * M5(a): Orature's port of the recorder app's `WacsLoginViewModel` — username/password (or
 * app-token) login against a WACS host, validated with a live `GET /user` call ("Test
 * connection") via [IWacsAuthenticator]. On success the credential is stashed in [WacsSession] —
 * in memory only, never persisted; a fresh process starts logged out.
 *
 * Deliberately identical in shape to `org.bibletranslationtools.bttrecorder2.ui.viewmodels.WacsLoginViewModel`
 * (the two apps keep their own screens/ViewModels — see `app-orature/build.gradle.kts` — so this
 * is a mirrored copy, not a shared class) since both depend only on `:shared` types.
 *
 * Hosted as a Koin factory (one instance per navigation to
 * [org.bibletranslationtools.orature.ui.navigation.OratureWacsPublishRoute] or
 * [org.bibletranslationtools.orature.ui.navigation.OratureWacsPullRoute]), but [WacsSession]
 * itself is the process-lifetime singleton, so re-opening either screen after a successful login
 * re-reads the still-live credential instead of forcing a re-login.
 */
class OratureWacsLoginViewModel(
    private val authenticator: IWacsAuthenticator,
    private val session: WacsSession,
    config: WacsConfig,
) : ViewModel() {

    private val _state = MutableStateFlow(
        OratureWacsLoginUiState(
            host = config.baseUrl,
            loggedInAs = (session.credential as? WacsCredential.Basic)?.username
        )
    )
    val state: StateFlow<OratureWacsLoginUiState> = _state.asStateFlow()

    fun setHost(value: String) = _state.update { it.copy(host = value, error = null) }
    fun setUsername(value: String) = _state.update { it.copy(username = value, error = null) }
    fun setPassword(value: String) = _state.update { it.copy(password = value, error = null) }

    fun testConnection() {
        val current = _state.value
        if (current.host.isBlank() || current.username.isBlank() || current.password.isBlank()) {
            viewModelScope.launch {
                _state.update { it.copy(error = getString(Res.string.wacs_login_error_missing_fields)) }
            }
            return
        }
        if (current.isLoading) return

        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val credential = authenticator.login(current.host, current.username, current.password)
                session.set(credential, current.host)
                _state.update {
                    it.copy(
                        isLoading = false,
                        loggedInAs = current.username,
                        password = "" // don't linger in the form once it's safely in WacsSession
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: WacsAuthException) {
                val message = when (e.reason) {
                    WacsAuthException.Reason.INVALID_CREDENTIALS ->
                        getString(Res.string.wacs_login_error_invalid_credentials)
                    WacsAuthException.Reason.SERVER_ERROR ->
                        getString(Res.string.wacs_login_error_server)
                    WacsAuthException.Reason.UNREACHABLE ->
                        getString(Res.string.wacs_login_error_unreachable)
                    WacsAuthException.Reason.UNKNOWN ->
                        getString(Res.string.wacs_login_error_generic)
                }
                _state.update { it.copy(isLoading = false, error = message) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(isLoading = false, error = getString(Res.string.wacs_login_error_generic))
                }
            }
        }
    }

    fun logout() {
        session.clear()
        _state.update { it.copy(loggedInAs = null, password = "") }
    }

    fun dismissError() = _state.update { it.copy(error = null) }
}

data class OratureWacsLoginUiState(
    val host: String = "",
    val username: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    /** Non-null while [WacsSession] holds a credential — the username it was validated for. */
    val loggedInAs: String? = null,
) {
    val isLoggedIn: Boolean get() = loggedInAs != null
}
