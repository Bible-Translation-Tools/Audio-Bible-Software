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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.domain.wacs.WacsPlatform
import org.bibletranslationtools.otter.common.domain.wacs.auth.WacsCredential
import org.bibletranslationtools.otter.common.domain.wacs.lfs.LfsPointer
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.WacsAndroidFS
import java.io.File

/**
 * JGit-backed [IWacsGitClient]. Installs [GitConfigIsolation] on construction so no host git config
 * (notably `filter.lfs.*`) leaks in — guaranteeing pointer-preserving, binary-free behavior on both
 * Android and Desktop. All JGit calls are blocking, so each runs on [Dispatchers.IO].
 *
 * On the Android runtime it uses [WacsAndroidFS], which implements file metadata via `java.io.File`
 * instead of `java.nio.file` POSIX permissions/attributes — Android's desugared nio provider fails on
 * `getPosixFilePermissions` (repo init) and `PosixFileAttributeView` (working-tree scans), so JGit's
 * default `FS_POSIX` path crashes there. On Desktop the platform-detected [FS] is used.
 */
class JGitWacsGitClient : IWacsGitClient {

    init {
        GitConfigIsolation.install()
    }

    /** Fail fast (rather than a cryptic JGit error) when git sync is not supported on this platform. */
    private fun requireSupported() = check(WacsPlatform.isGitSyncSupported) {
        "WACS git sync requires Android 8 (API 26)+ on this device."
    }

    /**
     * A FRESH filesystem per JGit operation. On Android, reusing one [WacsAndroidFS] instance across
     * a clone and a later reopen corrupts pack access (reopen throws MissingObjectException even
     * though the pack is on disk and a fresh instance reads it fine); a new instance per operation
     * avoids it. On Desktop the platform singleton [FS.DETECTED] is safe to reuse and returned as-is.
     */
    private fun newFs(): FS = if (isAndroidRuntime()) WacsAndroidFS() else FS.DETECTED

    override suspend fun clone(
        remoteUrl: String,
        dir: File,
        credential: WacsCredential,
        branch: String,
    ) = withContext(Dispatchers.IO) {
        requireSupported()
        Git.cloneRepository()
            .setURI(remoteUrl)
            .setDirectory(dir)
            .setBranch(branch)
            .setFs(newFs())
            .setCredentialsProvider(credential.toProvider())
            .call()
            .use { /* close immediately; callers reopen with open(dir) when needed */ }
    }

    override suspend fun fetchAndFastForward(
        dir: File,
        credential: WacsCredential,
        branch: String,
    ): Boolean = withContext(Dispatchers.IO) {
        requireSupported()
        open(dir).use { git ->
            git.fetch().setCredentialsProvider(credential.toProvider()).call()
            val upstream = git.repository.resolve("${Constants.DEFAULT_REMOTE_NAME}/$branch")
                ?: error("No origin/$branch to fast-forward from")
            val result = git.merge()
                .include(upstream)
                .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
                .call()
            when (result.mergeStatus) {
                MergeResult.MergeStatus.FAST_FORWARD -> true
                MergeResult.MergeStatus.ALREADY_UP_TO_DATE -> false
                else -> error("Cannot fast-forward $branch: ${result.mergeStatus} (someone else changed it)")
            }
        }
    }

    override suspend fun commit(
        dir: File,
        paths: List<String>,
        message: String,
        authorName: String,
        authorEmail: String,
    ): String = withContext(Dispatchers.IO) {
        requireSupported()
        open(dir).use { git ->
            val add = git.add()
            paths.forEach { add.addFilepattern(it) }
            add.call()
            git.commit()
                .setMessage(message)
                .setAuthor(authorName, authorEmail)
                .setSign(false)
                .call()
                .name
        }
    }

    override suspend fun push(
        dir: File,
        credential: WacsCredential,
        branch: String,
    ) = withContext(Dispatchers.IO) {
        requireSupported()
        open(dir).use { git ->
            val results = git.push()
                .setCredentialsProvider(credential.toProvider())
                .setRefSpecs(RefSpec("HEAD:refs/heads/$branch"))
                .call()
            val rejected = results.flatMap { it.remoteUpdates }.filterNot {
                it.status == RemoteRefUpdate.Status.OK || it.status == RemoteRefUpdate.Status.UP_TO_DATE
            }
            if (rejected.isNotEmpty()) {
                val detail = rejected.joinToString { "${it.remoteName}: ${it.status} ${it.message ?: ""}".trim() }
                error("Push rejected -> $detail")
            }
        }
    }

    override suspend fun listLfsPointers(dir: File): List<IWacsGitClient.TrackedObject> =
        withContext(Dispatchers.IO) {
        requireSupported()
            open(dir).use { git ->
                val repo: Repository = git.repository
                val head = repo.resolve(Constants.HEAD) ?: return@use emptyList()
                val out = mutableListOf<IWacsGitClient.TrackedObject>()
                repo.newObjectReader().use { reader ->
                    RevWalk(repo).use { walk ->
                        val tree = walk.parseCommit(head).tree
                        TreeWalk(repo).use { tw ->
                            tw.addTree(tree)
                            tw.isRecursive = true
                            while (tw.next()) {
                                val loader = reader.open(tw.getObjectId(0))
                                // Only small blobs can be pointers; skip large real content cheaply.
                                if (loader.size > 1024) continue
                                LfsPointer.parse(loader.bytes)?.let {
                                    out += IWacsGitClient.TrackedObject(tw.pathString, it)
                                }
                            }
                        }
                    }
                }
                out
            }
        }

    /** Open an existing clone with the platform-appropriate [fs]. */
    private fun open(dir: File): Git = Git.open(dir, newFs())

    private fun WacsCredential.toProvider(): CredentialsProvider = when (this) {
        is WacsCredential.Basic -> UsernamePasswordCredentialsProvider(username, secret)
    }

    private companion object {
        fun isAndroidRuntime(): Boolean =
            System.getProperty("java.runtime.name")?.contains("Android", ignoreCase = true) == true ||
                System.getProperty("java.vm.name")?.contains("Dalvik", ignoreCase = true) == true
    }
}
