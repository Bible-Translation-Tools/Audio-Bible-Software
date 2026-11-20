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
    /** The fingerprint of [parsedTree], the text of [container] as parsed, before any gap-filling. */
    suspend fun fingerprint(
        container: ResourceContainer,
        parsedTree: OtterTree<CollectionOrContent>
    ): EditionFingerprint =
        fingerprint(
            parsedTree = parsedTree,
            detectedVersification = structurePlanner.detect(container, parsedTree)?.code
        )

    fun fingerprint(
        parsedTree: OtterTree<CollectionOrContent>,
        detectedVersification: String?
    ): EditionFingerprint = EditionFingerprint.of(parsedTree, detectedVersification)

    /** The fingerprint of the source stored at [path], read from its files. */
    suspend fun fingerprint(path: File): EditionFingerprint =
        ResourceContainer.load(path, OtterResourceContainerConfig()).use { container ->
            val tree = IProjectReader.constructContainerTree(container, zipEntryTreeBuilder)
            fingerprint(container, tree)
        }
}
