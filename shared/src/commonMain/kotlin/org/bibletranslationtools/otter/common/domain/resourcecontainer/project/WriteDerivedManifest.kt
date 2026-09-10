package org.bibletranslationtools.otter.common.domain.resourcecontainer.project

import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.domain.resourcecontainer.RcConstants
import org.wycliffeassociates.resourcecontainer.ResourceContainer
import org.wycliffeassociates.resourcecontainer.entity.Checking
import org.wycliffeassociates.resourcecontainer.entity.DublinCore
import org.wycliffeassociates.resourcecontainer.entity.Manifest
import org.wycliffeassociates.resourcecontainer.entity.Project
import org.wycliffeassociates.resourcecontainer.entity.Source
import java.io.File
import java.time.LocalDate
import org.wycliffeassociates.resourcecontainer.entity.Language as RcLanguage

/**
 * Writes the manifest of a project derived from a source, for a container being assembled outside a
 * workbook.
 *
 * Project import reads a container's identity from its manifest: the target language it looks up,
 * the source it matches an existing collection against, and the book it derives. Anything that
 * builds a container to feed back into import therefore has to describe the project the same way
 * Orature's own derived containers do, which is what this centralizes.
 *
 * It exists because the resource-container library is `:shared`'s own dependency and not on the
 * apps' compile classpath, so an app assembling a container cannot write a manifest itself.
 */
class WriteDerivedManifest {

    /**
     * Writes `manifest.yaml` into [dir], describing one book derived from [sourceMetadata] into
     * [targetLanguage].
     *
     * `version` is the source's own. Import overwrites it with the resolved source's version
     * anyway, and starting from the same value keeps the file honest if that ever stops happening.
     *
     * @param projectPath where the project's media sits inside the container, as the manifest
     *   spells it — the default is what a backup written by this app declares
     */
    fun execute(
        dir: File,
        targetLanguage: Language,
        sourceMetadata: ResourceMetadata,
        bookSlug: String,
        bookTitle: String,
        bookSort: Int,
        contributors: List<String> = emptyList(),
        projectPath: String = "./${RcConstants.MEDIA_DIR}"
    ) {
        val today = LocalDate.now().toString()
        val dublinCore = DublinCore(
            type = TYPE,
            format = FORMAT,
            identifier = sourceMetadata.identifier,
            title = sourceMetadata.title,
            subject = sourceMetadata.subject,
            language = RcLanguage(
                direction = targetLanguage.direction,
                identifier = targetLanguage.slug,
                title = targetLanguage.name
            ),
            source = mutableListOf(
                Source(
                    sourceMetadata.identifier,
                    sourceMetadata.language.slug,
                    sourceMetadata.version
                )
            ),
            rights = sourceMetadata.license,
            creator = DERIVED_CREATOR,
            contributor = contributors.toMutableList(),
            issued = today,
            modified = today,
            version = sourceMetadata.version
        )
        val project = Project(
            title = bookTitle,
            identifier = bookSlug,
            sort = bookSort,
            path = projectPath
        )

        dir.mkdirs()
        // create() rather than load(), the directory holding no manifest yet. It fills in the
        // conformsTo the importer checks, but only after the init block has run — so the manifest is
        // written afterwards, or it goes out with that field empty and import rejects the container
        // as outdated.
        val container = ResourceContainer.create(dir) {
            manifest = Manifest(dublinCore, listOf(project), Checking())
        }
        try {
            container.write()
        } finally {
            container.close()
        }
    }

    companion object {
        /**
         * What Orature records as the creator of a project it derived, and so part of what the
         * database matches a derived container on.
         */
        const val DERIVED_CREATOR = "OratureInfo.SUITE_NAME"

        private const val TYPE = "book"
        private const val FORMAT = "text/usfm"
    }
}
