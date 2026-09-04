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

    /**
     * M4: add/refresh an `upstream` remote pointing at [upstreamUrl] (the *official*
     * `AudioTranslation/<repo>`, not `origin` — which is the user's fork), fetch it, and
     * fast-forward-merge [branch] from it onto the currently checked-out branch. Run this after
     * [clone] and before writing/committing new files, so a fork that has fallen behind the
     * official repo (someone else's contribution merged upstream since the fork was created)
     * doesn't cause a later [push] to be rejected as non-fast-forward.
     *
     * Pointer-only and cheap: with [GitConfigIsolation] installed, nothing here ever smudges an
     * LFS pointer into real bytes, so this is an ordinary small-object git fetch + fast-forward,
     * not an LFS download. A brand-new fork is already even with upstream, so this is a no-op
     * (returns `false`) immediately after cloning it.
     *
     * Throws if the local branch can't fast-forward (diverged — e.g. an in-progress local commit
     * conflicts with upstream). Callers classify that the same way as [fetchAndFastForward]'s
     * "someone else changed it" case (see `PublishChapterToWacs.classifyGitFailure`) — no
     * line-level merge is attempted (see the plan doc, §6).
     */
    suspend fun syncFromUpstream(
        dir: File,
        upstreamUrl: String,
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
