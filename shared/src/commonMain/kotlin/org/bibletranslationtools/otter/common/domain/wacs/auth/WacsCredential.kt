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
 * v1 is [Basic] only (username + secret), held in memory for the session and never persisted
 * (see the plan). [OAuth] is reserved for later; add it as a subtype when that lands.
 */
sealed interface WacsCredential {

    /** The HTTP `Authorization` header value for this credential (e.g. for the LFS Batch API). */
    fun authorizationHeader(): String

    /**
     * Basic auth. [secret] is a password or — strongly preferred — a WACS app-password / token, so
     * it can be scoped and revoked without exposing the account password.
     */
    data class Basic(val username: String, val secret: String) : WacsCredential {
        @OptIn(ExperimentalEncodingApi::class)
        override fun authorizationHeader(): String =
            "Basic " + Base64.encode("$username:$secret".encodeToByteArray())

        override fun toString(): String = "WacsCredential.Basic(username=$username, secret=***)"
    }
}
