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
package org.bibletranslationtools.otter.common.domain.project.exporter.resourcecontainer

import io.reactivex.Completable
import org.bibletranslationtools.otter.common.data.primitives.Contributor
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.domain.content.ConcatenateAudio
import org.bibletranslationtools.otter.common.domain.content.FileNamer
import org.bibletranslationtools.otter.common.domain.project.exporter.IProjectExporter
import org.bibletranslationtools.otter.common.domain.resourcecontainer.project.ProjectFilesAccessor
import org.bibletranslationtools.otter.common.api.persistence.IFileIOFactory
import org.bibletranslationtools.otter.common.api.persistence.ITempFileProvider
import org.wycliffeassociates.resourcecontainer.ResourceContainer
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

abstract class RCProjectExporter(
    protected val fileIO: IFileIOFactory,
    protected val tempFiles: ITempFileProvider
) : IProjectExporter {

    protected fun makeExportFilename(workbook: Workbook, metadata: ResourceMetadata): String {
        val lang = workbook.target.language.slug
        val resource = if (workbook.source.language.slug == workbook.target.language.slug) {
            metadata.identifier
        } else {
            FileNamer.DEFAULT_RC_SLUG
        }
        val project = workbook.target.slug
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
        return "$lang-$resource-$project-$timestamp.zip"
    }

    protected fun restoreFileExtension(file: File, extension: String): File {
        val fileName = file.nameWithoutExtension + ".$extension"
        val target = file.parentFile.resolve(fileName)
        // using nio Files.move() instead of file.rename() for platform independent
        Files.move(
            file.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING
        )
        return target
    }

    protected fun setContributorInfo(
        contributors: List<Contributor>,
        projectFile: File
    ) {
        ResourceContainer.load(projectFile).use { rc ->
            rc.manifest.dublinCore.apply {
                contributor = contributors.map { it.name }.toMutableList()
            }
            rc.writeManifest()
        }
    }

    /** Never ported from the JavaFX app, so source export fails here. See SourceProjectExporter. */
    protected fun compileCompletedChapters(
        workbook: Workbook,
        resourceMetadata: ResourceMetadata,
        projectFilesAccessor: ProjectFilesAccessor
    ): Completable = TODO()
}
