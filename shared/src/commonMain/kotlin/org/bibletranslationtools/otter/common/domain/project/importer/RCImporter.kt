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
package org.bibletranslationtools.otter.common.domain.project.importer

import io.reactivex.Single
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import org.bibletranslationtools.otter.common.api.persistence.ITempFileProvider
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceMetadataRepository
import java.io.File
import java.io.InputStream

/**
 * An importer for Resource Container project format.
 */
abstract class RCImporter(
    private val directoryProvider: ITempFileProvider,
    private val resourceMetadataRepository: IResourceMetadataRepository
) : IProjectImporter {
    private var next: RCImporter? = null
    private val logger = LoggerFactory.getLogger(javaClass)

    abstract override fun import(
        file: File,
        callback: ProjectImporterCallback?,
        options: ImportOptions?
    ): Single<ImportResult>

    protected suspend fun passToNextImporterSuspend(
        file: File,
        callback: ProjectImporterCallback?,
        options: ImportOptions?
    ): ImportResult = next?.importSuspend(file, callback, options) ?: ImportResult.FAILED

    /** Imports [stream] through this importer, from a temporary file that is deleted afterwards. */
    protected suspend fun importAsStreamSuspend(filename: String, stream: InputStream): ImportResult =
        withContext(Dispatchers.IO) {
            val outFile = directoryProvider.createTempFile(filename, ".zip")
            try {
                runCatching {
                    outFile.outputStream().use { stream.copyTo(it) }
                    importSuspend(outFile)
                }
                    .onFailure { logger.error("Error in import, filename: $filename", it) }
                    .getOrThrow()
            } finally {
                stream.close()
                outFile.delete()
            }
        }

    fun setNext(next: RCImporter) {
        this.next = next
    }

    fun getNext() = next
}