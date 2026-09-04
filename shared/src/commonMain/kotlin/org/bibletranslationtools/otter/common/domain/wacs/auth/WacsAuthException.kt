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
 * A login failure, classified into a small domain vocabulary the UI can localize. This is what
 * [IWacsAuthenticator.login] throws — the transport-specific types (`retrofit2.HttpException`,
 * `java.io.IOException`) are mapped to a [Reason] *inside* `:shared`, so app/UI layers never depend
 * on the HTTP stack (which `:shared` deliberately keeps off the apps' compile classpath).
 */
class WacsAuthException(
    val reason: Reason,
    cause: Throwable? = null,
) : Exception(reason.name, cause) {
    enum class Reason {
        /** Server rejected the credential (HTTP 401/403) — wrong username or password/token. */
        INVALID_CREDENTIALS,

        /** Server-side error (HTTP 5xx). */
        SERVER_ERROR,

        /** Host unreachable / transport failure (could not connect). */
        UNREACHABLE,

        /** Anything else. */
        UNKNOWN,
    }
}
