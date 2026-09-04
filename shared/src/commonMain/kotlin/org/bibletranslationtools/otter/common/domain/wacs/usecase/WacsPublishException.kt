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
package org.bibletranslationtools.otter.common.domain.wacs.usecase

/**
 * A publish failure, classified into a small domain vocabulary the UI can localize — the M2
 * counterpart of [org.bibletranslationtools.otter.common.domain.wacs.auth.WacsAuthException].
 * [PublishChapterToWacs] is the only thing that throws this; every JGit exception
 * (`GitAPIException`, `TransportException`, ...), `retrofit2.HttpException`, and `java.io.IOException`
 * / LFS transfer failure it can encounter is caught and mapped to a [Reason] here, so the app/UI
 * layer never depends on those transport types — mirrors exactly how `BasicAuthenticator` keeps
 * `retrofit2`/HTTP types off the app's compile classpath.
 */
class WacsPublishException(
    val reason: Reason,
    cause: Throwable? = null,
) : Exception(reason.name, cause) {
    enum class Reason {
        /** No credential in [org.bibletranslationtools.otter.common.domain.wacs.auth.WacsSession] — the user needs to log in first. */
        NOT_AUTHENTICATED,

        /** Server rejected the credential (HTTP 401/403) partway through publish. */
        AUTH_REJECTED,

        /** Host unreachable / transport failure (could not connect, timed out, DNS, ...). */
        NETWORK,

        /** No `AudioTranslation/<repo>` exists yet for this workbook — nothing to fork. */
        NO_OFFICIAL_REPO,

        /** The chapter has no selected/finished take to publish. */
        NO_AUDIO,

        /** The remote rejected the push (non-fast-forward) or a fork-provisioning race. */
        CONFLICT,

        /** An LFS-uploaded object's re-verified hash didn't match what was sent. */
        INTEGRITY,

        /** Anything else. */
        UNKNOWN,
    }
}
