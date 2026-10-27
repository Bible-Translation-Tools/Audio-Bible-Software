package org.bibletranslationtools.otter.common.domain.project.importer

import org.bibletranslationtools.otter.common.collections.OtterTree
import org.bibletranslationtools.otter.common.data.primitives.CollectionOrContent
import org.bibletranslationtools.otter.common.domain.resourcecontainer.EditionFingerprint
import org.bibletranslationtools.otter.common.domain.resourcecontainer.OtterResourceContainerConfig
import org.bibletranslationtools.otter.common.domain.resourcecontainer.project.IProjectReader
import org.bibletranslationtools.otter.common.domain.resourcecontainer.project.IZipEntryTreeBuilder
import org.wycliffeassociates.resourcecontainer.ResourceContainer
import java.io.File

/** Builds a source edition's [EditionFingerprint] from its parsed text. */
class EditionFingerprinter(
    private val structurePlanner: SourceStructurePlanner,
    private val zipEntryTreeBuilder: IZipEntryTreeBuilder
) {
    /**
     * The fingerprint of [parsedTree], the text of [container] as parsed, before any gap-filling.
     * Pass [detectedVersification] when it is already known, to skip detecting it again.
     */
    fun fingerprint(
        container: ResourceContainer,
        parsedTree: OtterTree<CollectionOrContent>,
        detectedVersification: String? = structurePlanner.detect(container, parsedTree)?.code
    ): EditionFingerprint = EditionFingerprint.of(parsedTree, detectedVersification)

    /** The fingerprint of the source stored at [path], read from its files. */
    fun fingerprint(path: File): EditionFingerprint =
        ResourceContainer.load(path, OtterResourceContainerConfig()).use { container ->
            fingerprint(container, IProjectReader.constructContainerTree(container, zipEntryTreeBuilder))
        }
}
