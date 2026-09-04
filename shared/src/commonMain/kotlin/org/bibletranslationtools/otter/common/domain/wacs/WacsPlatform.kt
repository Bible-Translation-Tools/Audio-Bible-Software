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

/**
 * Platform capability gate for WACS git sync.
 *
 * JGit's object database relies on `java.nio.file` operations that are native on Android 8 (API 26)+
 * but only partially covered by library desugaring on API 24/25, where clone/commit/push fail at
 * runtime (`MissingObjectException`) — verified on emulators (API 33 green, API 24 red). Desktop is
 * always supported. UI should hide/disable the sync feature when [isGitSyncSupported] is false;
 * [JGitWacsGitClient] also guards every operation defensively.
 */
expect object WacsPlatform {
    val isGitSyncSupported: Boolean
}
