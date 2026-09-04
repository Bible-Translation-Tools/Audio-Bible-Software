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

// NOTE: this file deliberately lives in JGit's own package `org.eclipse.jgit.util` so it can call
// the package-private `FS.Attributes` constructor. That is the only way to give JGit a filesystem
// that never touches `java.nio.file` POSIX attributes — which Android's desugared nio provider does
// not implement (Files.getPosixFilePermissions / PosixFileAttributeView both fail on API < 26/33).
// See org.bibletranslationtools.otter.common.domain.wacs.git.JGitWacsGitClient for why this exists.
package org.eclipse.jgit.util

import java.io.File

/**
 * A [FS_POSIX] for the Android runtime that implements file metadata with `java.io.File` instead of
 * `java.nio.file` POSIX attributes/permissions. JGit's default `FS_POSIX` calls
 * `Files.getPosixFilePermissions` (repo init) and `PosixFileAttributeView.readAttributes` (working
 * tree scans) — both unsupported by Android's desugared nio — so clone/checkout crash without this.
 *
 * git file-mode / exec-bit and symlinks are irrelevant to WACS content (LFS pointers + audio), so we
 * report `supportsExecute = false` and non-symlink attributes.
 */
class WacsAndroidFS : FS_POSIX {

    constructor() : super()
    private constructor(src: WacsAndroidFS) : super(src)

    override fun newInstance(): FS = WacsAndroidFS(this)

    override fun supportsExecute(): Boolean = false

    override fun canExecute(f: File): Boolean = f.canExecute()

    override fun setExecute(f: File, canExecute: Boolean): Boolean = f.setExecutable(canExecute, false)

    // Use JGit's own BASIC (non-POSIX) attributes helper. It reads java.nio BasicFileAttributes —
    // supported by Android's desugared nio (only the POSIX view is not) — so the mtime/size here
    // match what JGit's FileSnapshot reads elsewhere. A hand-rolled File.lastModified() version
    // disagreed with FileSnapshot and made JGit treat freshly-written packs as modified, dropping
    // them (MissingObjectException on reopen).
    override fun getAttributes(path: File): FS.Attributes = FileUtils.getFileAttributesBasic(this, path)
}
