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

/**
 * Validates credentials against a WACS host and hands back a [WacsCredential] on success. Behind
 * this thin abstraction so OAuth2 (PKCE) can drop in later as a second implementation without
 * touching callers (the login screen, [WacsSession]) — see the plan, §7.
 *
 * v1 is [BasicAuthenticator] only: username/password (or, preferably, a WACS app-password/token),
 * validated with a live `GET /user` call. Nothing here persists the result — that is [WacsSession]'s
 * job, and only in memory.
 */
interface IWacsAuthenticator {
    /**
     * Validate [username]/[secret] against [host] (a base URL, e.g. `"http://localhost:3000/"` —
     * need not end in `/`). Returns the validated credential on success; throws [WacsAuthException]
     * (classified: bad credentials / server error / unreachable / unknown) on failure, so callers
     * localize by [WacsAuthException.reason] without touching HTTP types.
     */
    suspend fun login(host: String, username: String, secret: String): WacsCredential
}
