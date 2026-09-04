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

import org.bibletranslationtools.otter.common.domain.wacs.api.WacsApiFactory
import retrofit2.HttpException
import java.io.IOException

/**
 * [IWacsAuthenticator] backed by HTTP Basic auth: builds a [org.bibletranslationtools.otter.common.domain.wacs.api.ForgejoApi]
 * for [host] via [apiFactory] and calls `GET /user` (the "Test connection" check) with the
 * candidate credential's `Authorization` header. The transport-specific failures (`HttpException`,
 * `IOException`) are classified into a [WacsAuthException.Reason] here — keeping `retrofit2`/HTTP
 * types out of the app/UI layer, which only sees the domain [WacsAuthException].
 */
class BasicAuthenticator(
    private val apiFactory: WacsApiFactory,
) : IWacsAuthenticator {
    override suspend fun login(host: String, username: String, secret: String): WacsCredential {
        val credential = WacsCredential.Basic(username, secret)
        val api = apiFactory.create(host)
        try {
            api.whoAmI(credential.authorizationHeader()) // result unused — this call is the validation
        } catch (e: HttpException) {
            throw WacsAuthException(
                when {
                    e.code() == 401 || e.code() == 403 -> WacsAuthException.Reason.INVALID_CREDENTIALS
                    e.code() >= 500 -> WacsAuthException.Reason.SERVER_ERROR
                    else -> WacsAuthException.Reason.UNKNOWN
                },
                e,
            )
        } catch (e: IOException) {
            throw WacsAuthException(WacsAuthException.Reason.UNREACHABLE, e)
        }
        return credential
    }
}
