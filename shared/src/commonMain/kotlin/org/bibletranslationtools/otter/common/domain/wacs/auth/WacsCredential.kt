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

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * A WACS (Forgejo) credential. git and git-LFS share the same credential for a host, so the same
 * value drives JGit's `CredentialsProvider` and the LFS Batch API `Authorization` header.
 *
 * Held in memory for the session and never persisted (see the plan). Signing in turns the user's
 * password into a [Token], which is what the publish pipeline accepts; [Basic] is what the password
 * is checked and the token created with. OAuth, when it lands, produces a [Token] too.
 */
sealed interface WacsCredential {

    /** The WACS account this credential signs in as; git sends it with the secret. */
    val username: String

    /** The secret git sends with [username]: a password or a token. */
    val gitPassword: String

    /** The HTTP `Authorization` header for the WACS API and the publish pipeline. */
    fun authorizationHeader(): String

    /**
     * The `Authorization` header git and git-LFS take: Basic, with [gitPassword] as the password.
     * WACS's LFS endpoint reads `token`/`Bearer` as its own LFS tokens, not access tokens, so an
     * access token has to go this way there.
     */
    fun gitAuthorizationHeader(): String = Basic(username, gitPassword).authorizationHeader()

    /** A username and password (or a token used as the password). */
    data class Basic(override val username: String, val secret: String) : WacsCredential {
        override val gitPassword: String get() = secret

        @OptIn(ExperimentalEncodingApi::class)
        override fun authorizationHeader(): String =
            "Basic " + Base64.encode("$username:$secret".encodeToByteArray())

        override fun toString(): String = "WacsCredential.Basic(username=$username, secret=***)"
    }

    /** A WACS access token for [username], sent as `Authorization: token <token>`. */
    data class Token(override val username: String, val token: String) : WacsCredential {
        override val gitPassword: String get() = token

        override fun authorizationHeader(): String = "token $token"

        override fun toString(): String = "WacsCredential.Token(username=$username, token=***)"
    }
}
