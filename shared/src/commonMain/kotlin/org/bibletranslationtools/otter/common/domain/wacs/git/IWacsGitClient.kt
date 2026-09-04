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
package org.bibletranslationtools.otter.common.domain.wacs.git

import org.bibletranslationtools.otter.common.domain.wacs.auth.WacsCredential
import org.bibletranslationtools.otter.common.domain.wacs.lfs.LfsPointer
import java.io.File

/**
 * The git plumbing for WACS sync: clone/fetch/commit/push driven by JGit (pure Java — no `git`
 * binary), with LFS transfer handled separately by
 * [org.bibletranslationtools.otter.common.domain.wacs.lfs.LfsBatchClient]. Implementations MUST run
 * with host git config isolated (see [GitConfigIsolation]) so no ambient `filter.lfs.*` ever makes
 * JGit shell out to a system `git-lfs`; the working tree therefore holds LFS *pointers*, which is
 * what [listLfsPointers] reads and what [commit] stages.
 */
interface IWacsGitClient {

    /** An LFS-tracked file in the tree: its repo-relative [path] and the [pointer] committed there. */
    data class TrackedObject(val path: String, val pointer: LfsPointer)

    /** Clone [remoteUrl] (default branch [branch]) into [dir]. Checks out pointers, not audio. */
    suspend fun clone(
        remoteUrl: String,
        dir: File,
        credential: WacsCredential,
        branch: String = DEFAULT_BRANCH,
    )

    /** Fetch [branch] from origin and fast-forward the local branch. Returns true if it moved. */
    suspend fun fetchAndFastForward(
        dir: File,
        credential: WacsCredential,
        branch: String = DEFAULT_BRANCH,
    ): Boolean

    /** Stage [paths] (repo-relative) and commit. Returns the new commit's SHA-1. */
    suspend fun commit(
        dir: File,
        paths: List<String>,
        message: String,
        authorName: String,
        authorEmail: String,
    ): String

    /** Push local [branch] to origin. Throws if the remote rejects the update. */
    suspend fun push(
        dir: File,
        credential: WacsCredential,
        branch: String = DEFAULT_BRANCH,
    )

    /** Read every LFS pointer committed at HEAD (repo-relative path + parsed pointer). */
    suspend fun listLfsPointers(dir: File): List<TrackedObject>

    companion object {
        const val DEFAULT_BRANCH = "master"
    }
}
