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
package org.bibletranslationtools.otter.common.domain.wacs

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.bibletranslationtools.otter.common.domain.wacs.api.WacsApiFactory
import org.bibletranslationtools.otter.common.domain.wacs.auth.WacsCredential
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Sanity check for the DI wiring shape (M0 remaining item): builds a [ForgejoApi] the same way
 * `wacsModule` does — [WacsApiFactory] over a plain [OkHttpClient] — and calls `whoAmI` against
 * the local prototype. Not a substitute for [WacsSyncDesktopTest] (which also exercises JGit/LFS);
 * this one only pins that the converter wiring in [WacsApiFactory] actually works end-to-end.
 * Skipped unless WACS_USER/WACS_PASS are set (keeps `check` green).
 * Run: `WACS_USER=user1 WACS_PASS=user1234 ./gradlew :shared:desktopTest --tests "*WacsApiFactoryDesktopTest*"`.
 */
class WacsApiFactoryDesktopTest {

    @Test
    fun whoAmI_via_the_DI_built_ForgejoApi() {
        val user = System.getenv("WACS_USER")
        val pass = System.getenv("WACS_PASS")
        if (user.isNullOrBlank() || pass.isNullOrBlank()) return // skip when no creds
        val baseUrl = (System.getenv("WACS_BASE_URL") ?: "http://localhost:3000").trimEnd('/')
        val cred = WacsCredential.Basic(user, pass)

        val api = WacsApiFactory(OkHttpClient()).create(baseUrl)

        runBlocking {
            val me = api.whoAmI(cred.authorizationHeader())
            assertEquals(user, me.login)
        }
    }
}
