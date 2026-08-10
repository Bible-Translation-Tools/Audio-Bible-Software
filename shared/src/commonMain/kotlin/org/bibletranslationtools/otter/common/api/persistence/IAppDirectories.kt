/**
 * Copyright (C) 2020-2024 Wycliffe Associates
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
package org.bibletranslationtools.otter.common.api.persistence

import java.io.File

/**
 * The fixed, per-installation locations the app owns: where user documents go, where private
 * app data goes, and the well-known subdirectories carved out of those two.
 *
 * Everything here is a property of the installation, not of any project or resource container —
 * ask for [IProjectDirectories] or [IResourceContainerDirectories] for those.
 */
interface IAppDirectories {

    /** Directory to store the user's application projects/documents */
    fun getUserDataDirectory(appendedPath: String = ""): File

    /** Directory to store the application's private data */
    fun getAppDataDirectory(appendedPath: String = ""): File

    val databaseDirectory: File
    val versificationDirectory: File
    val audioPluginDirectory: File
    val userProfileImageDirectory: File
    val userProfileAudioDirectory: File
    val logsDirectory: File
    val cacheDirectory: File
}
