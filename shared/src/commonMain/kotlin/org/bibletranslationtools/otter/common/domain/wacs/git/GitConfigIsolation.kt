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

import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import java.io.File

/**
 * Makes JGit ignore the host's global, system, and JGit-level git config.
 *
 * Why this exists: JGit honors `filter.lfs.*` from the user's **global** gitconfig. On a machine
 * where `git-lfs` is installed and globally configured (any developer machine, and some users'),
 * JGit will silently shell out to the system `git-lfs` binary during checkout — smudging LFS
 * pointers into real bytes. Our design hand-rolls LFS transfer and needs JGit to leave the
 * ~128-byte pointers in the working tree, identically on Android and Desktop. Isolating JGit from
 * host git config guarantees that, and also makes commits/checkouts deterministic (no inherited
 * `user.*`, `core.autocrlf`, hooks, or filters leaking in from the machine).
 *
 * We do NOT require `git` or `git-lfs` to be installed — JGit is pure-Java. This isolation only
 * removes *inherited configuration*; it changes nothing about JGit's ability to clone/commit/push.
 *
 * Install ONCE, early in app startup, before any JGit repository is opened. Idempotent and thread
 * safe. Verified against JGit 6.10.x.
 */
object GitConfigIsolation {

    @Volatile
    private var installed = false

    /** Install the isolated [SystemReader]. Safe to call multiple times; only the first takes effect. */
    @Synchronized
    fun install() {
        if (installed) return
        SystemReader.setInstance(IsolatedSystemReader(SystemReader.getInstance()))
        installed = true
    }

    /**
     * A [SystemReader] that delegates everything to the platform default except the three git
     * config sources, which it replaces with empty configs backed by a nonexistent file (loads as
     * empty, never written). The repository-local `.git/config` is unaffected — we still read and
     * write it — so per-repo settings we set ourselves continue to work.
     */
    private class IsolatedSystemReader(private val delegate: SystemReader) : SystemReader() {

        // Empty, read-only config source. A FileBasedConfig over a path that does not exist loads
        // as empty; we never call save() on these, so nothing is ever created on disk.
        private fun empty(parent: Config?, fs: FS): FileBasedConfig {
            val nowhere = File(
                System.getProperty("java.io.tmpdir"),
                "jgit-isolated-empty.config" // stable name; file is never created
            )
            return FileBasedConfig(parent, nowhere, fs)
        }

        override fun openUserConfig(parent: Config?, fs: FS): FileBasedConfig = empty(parent, fs)
        override fun openSystemConfig(parent: Config?, fs: FS): FileBasedConfig = empty(parent, fs)
        override fun openJGitConfig(parent: Config?, fs: FS): FileBasedConfig = empty(parent, fs)

        // Everything else: platform default behavior.
        override fun getHostname(): String = delegate.hostname
        override fun getenv(variable: String?): String? = delegate.getenv(variable)
        override fun getProperty(key: String?): String? = delegate.getProperty(key)
        override fun getCurrentTime(): Long = delegate.currentTime
        override fun getTimezone(whenTime: Long): Int = delegate.getTimezone(whenTime)
    }
}
