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

import org.bibletranslationtools.otter.common.api.io.zip.IFileReader
import org.bibletranslationtools.otter.common.api.io.zip.IFileWriter
import java.io.File

/**
 * Opens a file or directory as a readable/writable tree, hiding whether it is a zip or a
 * plain directory on disk — the reason import and export can treat both alike.
 *
 * This is a factory, not a location: it has nothing to do with where the app keeps things,
 * and only ended up on the directory provider because that was the object everyone already had.
 */
interface IFileIOFactory {

    /** Create a new IFileWriter */
    fun newFileWriter(file: File): IFileWriter

    /** Create a new IFileReader */
    fun newFileReader(file: File): IFileReader
}
