package org.bibletranslationtools.otter.common.domain.resourcecontainer.project

import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.domain.resourcecontainer.SourceAudioAccessor
import java.util.concurrent.ConcurrentHashMap

/**
 * A project's files: its folder, takes, chunk file, source copy and source audio.
 *
 * The workbook used to build these accessors itself, which made the innermost domain object a
 * factory for file system access and forced every caller that wanted a path to take a whole
 * workbook. Inject this instead. Accessors are kept per project, so callers share one.
 */
class ProjectFilesService(private val directoryProvider: IDirectoryProvider) {

    private val projects = ConcurrentHashMap<Triple<Int, Int, Int>, ProjectFilesAccessor>()
    private val sources = ConcurrentHashMap<Pair<Int, String>, SourceAudioAccessor>()

    /** [workbook]'s project files. */
    fun of(workbook: Workbook): ProjectFilesAccessor =
        projects.computeIfAbsent(
            Triple(workbook.source.resourceMetadata.id, workbook.target.resourceMetadata.id, workbook.target.collectionId)
        ) {
            ProjectFilesAccessor(
                directoryProvider,
                workbook.source.resourceMetadata,
                workbook.target.resourceMetadata,
                workbook.target.toCollection()
            )
        }

    /** [workbook]'s source audio. */
    fun sourceAudio(workbook: Workbook): SourceAudioAccessor =
        sources.computeIfAbsent(workbook.source.resourceMetadata.id to workbook.source.slug) {
            SourceAudioAccessor(directoryProvider, workbook.source.resourceMetadata, workbook.source.slug)
        }
}
