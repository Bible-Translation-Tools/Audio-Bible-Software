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
 * A pull-as-source failure, classified into a small domain vocabulary the UI can localize — the M3
 * counterpart of [WacsPublishException] (which itself mirrors
 * [org.bibletranslationtools.otter.common.domain.wacs.auth.WacsAuthException]). [ListWacsRepos],
 * [CloneWacsRepo], and [PullChapter] are the only things that throw this; every JGit exception,
 * `retrofit2.HttpException`, and `java.io.IOException`/LFS transfer failure they can encounter is
 * caught and mapped to a [Reason] here, so the app/UI layer never depends on those transport types —
 * see the plan doc's "error-boundary rule".
 */
class WacsPullException(
    val reason: Reason,
    cause: Throwable? = null,
) : Exception(reason.name, cause) {
    enum class Reason {
        /** No credential in [org.bibletranslationtools.otter.common.domain.wacs.auth.WacsSession] — the user needs to log in first. */
        NOT_AUTHENTICATED,

        /** Server rejected the credential (HTTP 401/403). */
        AUTH_REJECTED,

        /** Host unreachable / transport failure (could not connect, timed out, DNS, ...). */
        NETWORK,

        /** No such repo in the `AudioTranslation` org (HTTP 404 on `getRepo`). */
        REPO_NOT_FOUND,

        /** The repo cloned, but its `metadata.json` is missing or unreadable — nothing to show. */
        NO_SCOPE,

        /** The requested book/chapter has no audio ingredient in the repo's current scope. */
        CHAPTER_NOT_FOUND,

        /** An LFS-downloaded object's hash didn't match its pointer's `oid` (see [LfsBatchClient][org.bibletranslationtools.otter.common.domain.wacs.lfs.LfsBatchClient]). */
        INTEGRITY,

        /** Anything else. */
        UNKNOWN,
    }
}
