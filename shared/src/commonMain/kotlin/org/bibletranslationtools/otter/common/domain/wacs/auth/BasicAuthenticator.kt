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
package org.bibletranslationtools.otter.common.domain.wacs.auth

import org.bibletranslationtools.otter.common.domain.wacs.api.CreateTokenRequest
import org.bibletranslationtools.otter.common.domain.wacs.api.WacsApiFactory
import org.slf4j.LoggerFactory
import retrofit2.HttpException
import java.io.IOException

/**
 * [IWacsAuthenticator] that signs in with a username and password and comes back with a WACS
 * access token, which is what the publish pipeline accepts. It checks the password with
 * `GET /user` (the "Test connection" check), then creates a token for this session with the
 * scopes publishing and restoring need. Forgejo creates tokens only for a password, so when what
 * was typed is already a token, WACS refuses and that token is used as is.
 *
 * The token is held in memory like any credential. Each sign-in creates a new one on the server;
 * OAuth will replace this. The transport-specific failures (`HttpException`, `IOException`) are
 * classified into a [WacsAuthException.Reason] here, so the app only sees [WacsAuthException].
 */
class BasicAuthenticator(
    private val apiFactory: WacsApiFactory,
) : IWacsAuthenticator {
    private val logger = LoggerFactory.getLogger(BasicAuthenticator::class.java)

    override suspend fun login(host: String, username: String, secret: String): WacsCredential {
        val password = WacsCredential.Basic(username, secret)
        val api = apiFactory.create(host)
        return classified {
            val user = api.whoAmI(password.authorizationHeader())
            val token = try {
                api.createToken(
                    user.login,
                    password.authorizationHeader(),
                    CreateTokenRequest(name = "$TOKEN_NAME ${System.currentTimeMillis()}", scopes = TOKEN_SCOPES),
                ).also { logger.info("Signed in to {} as {}; created access token \"{}\"", host, user.login, it.name) }.sha1
            } catch (e: HttpException) {
                if (e.code() != 401 && e.code() != 403) throw e
                logger.info("Signed in to {} as {}; WACS won't create a token from this secret, so it's used as the token", host, user.login)
                secret
            }
            WacsCredential.Token(user.login, token)
        }
    }

    private suspend fun <T> classified(call: suspend () -> T): T = try {
        call()
    } catch (e: HttpException) {
        val request = e.response()?.raw()?.request()
        logger.warn("Signing in failed: HTTP {} for {} {}", e.code(), request?.method(), request?.url()?.let { it.host() + it.encodedPath() })
        throw WacsAuthException(
            when {
                e.code() == 401 || e.code() == 403 -> WacsAuthException.Reason.INVALID_CREDENTIALS
                e.code() >= 500 -> WacsAuthException.Reason.SERVER_ERROR
                else -> WacsAuthException.Reason.UNKNOWN
            },
            e,
        )
    } catch (e: IOException) {
        logger.warn("Signing in failed: the server can't be reached", e)
        throw WacsAuthException(WacsAuthException.Reason.UNREACHABLE, e)
    }

    private companion object {
        /** Each token's name, made unique by the time it was created; Forgejo requires that. */
        const val TOKEN_NAME = "BTT WACS sync"

        /**
         * Publishing forks, pushes and opens pull requests (`write:repository`), and the pipeline
         * reads who the user is (`write:user` covers `read:user`). Restore lists the organization's
         * repos (`read:organization`).
         */
        val TOKEN_SCOPES = listOf("write:repository", "write:user", "read:organization")
    }
}
