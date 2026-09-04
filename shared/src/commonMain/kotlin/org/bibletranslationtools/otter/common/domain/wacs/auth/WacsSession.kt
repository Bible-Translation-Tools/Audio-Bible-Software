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
 * Process-lifetime, in-memory holder for the current [WacsCredential] — deliberately NOT
 * persisted (no DataStore, no keystore, no disk write of any kind). The user re-authenticates
 * every app launch (and any time [clear] is called, e.g. an explicit logout). Persistence +
 * OAuth2 are deferred (see the plan, §7).
 *
 * Provided as a Koin singleton so every screen/use case that needs "is the user logged in, and
 * as whom" reads the same instance. [credential] is `@Volatile` because it can be written from a
 * ViewModel's coroutine and read from Compose recomposition on a different thread.
 */
class WacsSession {
    @Volatile
    var credential: WacsCredential? = null
        private set

    fun isLoggedIn(): Boolean = credential != null

    /** Called after a successful [IWacsAuthenticator.login] — never call with an unvalidated credential. */
    fun set(credential: WacsCredential) {
        this.credential = credential
    }

    /** Explicit logout. Also implicitly "logs out" on process death, since nothing is persisted. */
    fun clear() {
        credential = null
    }
}
