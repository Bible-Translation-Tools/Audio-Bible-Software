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
 * Scratch space: somewhere to put a file that only has to outlive the operation that made it.
 *
 * This is the most widely used slice of the old god interface — import, export, narration and
 * audio transforms all stage intermediate audio here. It is kept separate from
 * [IAppDirectories] because the lifetime guarantee is the point: anything under [tempDirectory]
 * may be erased by [cleanTempDirectory] on the next launch.
 */
interface ITempFileProvider {

    val tempDirectory: File

    /** Create temp file */
    fun createTempFile(prefix: String, suffix: String? = null): File

    /** Clean temporary directory */
    fun cleanTempDirectory()
}
