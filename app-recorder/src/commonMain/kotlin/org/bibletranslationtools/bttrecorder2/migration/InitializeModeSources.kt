package org.bibletranslationtools.bttrecorder2.migration

import org.bibletranslationtools.otter.common.api.io.IBundledContentSource
import org.bibletranslationtools.otter.common.api.persistence.ITempFileProvider
import org.bibletranslationtools.otter.common.api.persistence.repositories.ICollectionRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceMetadataRepository
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.domain.project.importer.NewSourceImporter
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import org.slf4j.LoggerFactory
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Imports the ULB source a migrated project is derived from, trimmed to the books it needs.
 *
 * A legacy project migrates as ULB whatever its legacy version slug said (`ulb`, `udb`, `reg`), so
 * both bundled texts carry `identifier: 'ulb'` and are told apart by dublin_core version:
 *
 * | version | bundled text | units it yields |
 * | ------- | ------------ | --------------- |
 * | `12-verse` | `en_ulb_verse.zip` | one per verse |
 * | `12-chunk` | `en_ulb_chunk.zip` | one per legacy chunk, as bridged verses |
 *
 * Picking the text picks the mode: a project's units come from its source's content rows, which is
 * how a migrated chunk-mode project keeps chunk units in a recorder that otherwise records verse by
 * verse. `ResourceContainerRepository.updateBridges` turns each `\v 1-3` in the chunk-mode text into
 * one row spanning `start=1, end=3` plus `bridged` fillers.
 *
 * Importing goes straight to [NewSourceImporter] rather than through `ImportProjectUseCase`. The
 * importer chain matches an existing source on language and identifier while ignoring the version,
 * so it would treat either text as an update of the plain `ulb` that `InitializeUlb` installs and
 * overwrite its content rows. Nothing imported here is an `.orature` project or an update of an
 * existing source, so the earlier links in that chain have nothing to contribute.
 *
 * Trimming to the referenced books keeps first-launch cost proportional to what was recorded:
 * versification preallocates a content row per verse in the container, and `updateBridges` issues an
 * update per bridged range — for the whole Bible that is ~31,000 rows and 13,608 updates against
 * ~356 for a single book. The zips are generated whole at build time and subset here, which is a zip
 * copy plus a manifest filter, with no USFM parsing. Only the modes in use are imported at all.
 *
 * The zips ship from `files/legacy/` rather than `files/content/`, which the project wizard
 * enumerates for sideload, so they stay inert until [MigrateLegacyRecorderProjects] finds legacy
 * data. One consequence of trimming: these sources hold only the migrated books, so choosing them in
 * the wizard offers just those. They exist to back migrated projects.
 */
class InitializeModeSources(
    private val resourceMetadataRepository: IResourceMetadataRepository,
    private val collectionRepository: ICollectionRepository,
    private val newSourceImporter: NewSourceImporter,
    private val tempFiles: ITempFileProvider,
    private val bundledContent: IBundledContentSource
) {

    private val logger = LoggerFactory.getLogger(InitializeModeSources::class.java)

    /**
     * Imports the text for each mode in [required], trimmed to the books asked of it.
     *
     * @param required mode to the book slugs needed from that mode's text
     * @return true when every required text covers every book asked of it. False leaves the caller to
     *   defer rather than skip projects permanently.
     */
    fun ensureImported(required: Map<LegacyMode, Set<String>>): Boolean {
        if (required.isEmpty()) return true
        return required.entries.fold(true) { ok, (mode, books) -> importIfNeeded(mode, books) && ok }
    }

    /**
     * A container holding [mode]'s text for [books], for a staged backup to carry as its source.
     *
     * A backup names its source in the manifest but the importer's lookup ignores the version, so a
     * backup that only names a source is bound to whichever text shares its identifier and language
     * — the plain `ulb` every install already has. Carrying the container makes the importer import
     * it first, and the source it then resolves is that one. This is how an Orature backup works;
     * without it a migrated chunk-mode project derives verse by verse.
     *
     * The caller owns the file and should delete it once the import is done.
     *
     * @return the container, or null when the bundled text has none of [books]
     */
    fun trimmedContainer(mode: LegacyMode, books: Set<String>): File? {
        val destination = tempFiles.createTempFile(bundledNameFor(mode), ".zip").also(File::deleteOnExit)
        return try {
            val kept = trimToBooks(mode, books, destination)
            if (kept.isEmpty()) {
                logger.error("${bundledNameFor(mode)} has none of ${books.sorted()}")
                destination.delete()
                null
            } else {
                if (kept != books) logger.warn("${bundledNameFor(mode)} has no ${books - kept}")
                destination
            }
        } catch (e: Exception) {
            logger.error("Could not build a ${bundledNameFor(mode)} container for ${books.sorted()}", e)
            runCatching { destination.delete() }
            null
        }
    }

    private fun importIfNeeded(mode: LegacyMode, books: Set<String>): Boolean {
        val version = versionFor(mode)
        val have = importedBooks(version)
        val missing = books - have
        if (missing.isEmpty()) {
            logger.info("$IDENTIFIER v$version already covers ${books.sorted()}; skipped.")
            return true
        }
        // The union, so books covered by an earlier run are not dropped when a later run needs
        // another.  The caller normally passes every book at once.
        val wanted = (have + books).sorted().toSet()
        logger.info("Importing $IDENTIFIER v$version trimmed to ${wanted.toList()} (missing $missing)...")

        var trimmed: File? = null
        return try {
            trimmed = tempFiles.createTempFile(bundledNameFor(mode), ".zip").also(File::deleteOnExit)
            val kept = trimToBooks(mode, wanted, trimmed)
            if (kept.isEmpty()) {
                logger.error("$IDENTIFIER v$version: none of $wanted are in the bundled text")
                return false
            }
            if (kept != wanted) {
                logger.warn("$IDENTIFIER v$version: bundled text has no ${wanted - kept}")
            }
            val result = newSourceImporter.import(trimmed, null, null).blockingGet()
            if (result != ImportResult.SUCCESS) {
                logger.error("Import of $IDENTIFIER v$version returned $result")
                return false
            }
            // An import can report SUCCESS and still leave no book collections behind, which
            // would otherwise surface only when a project failed to resolve its source.
            val now = importedBooks(version)
            val stillMissing = books - now
            if (stillMissing.isNotEmpty()) {
                logger.error("$IDENTIFIER v$version imported but has no collections for $stillMissing")
                return false
            }
            logger.info("$IDENTIFIER v$version imported, covering ${now.sorted()}")
            true
        } catch (e: Exception) {
            logger.error("Could not import $IDENTIFIER v$version", e)
            false
        } finally {
            runCatching { trimmed?.delete() }
        }
    }

    /**
     * Book slugs this source already has collections for, empty when it is not imported.
     *
     * Matching the version matters as much as the identifier: the plain `ulb` shares the identifier
     * and covers all 66 books, so an identifier-only match would report every book as already
     * covered and skip the import.
     */
    private fun importedBooks(version: String): Set<String> {
        val present = resourceMetadataRepository.getAllSources().blockingGet().any {
            matchesModeSource(it, version)
        }
        if (!present) return emptySet()
        return collectionRepository.getSourceProjects().blockingGet()
            .filter { matchesModeSource(it.resourceContainer, version) }
            .mapTo(mutableSetOf()) { it.slug }
    }

    /**
     * Copies the bundled text for [mode] to [destination], keeping only [books]' USFM and the
     * matching `projects:` entries. The dublin_core identity is stamped at build time and is left
     * alone.
     *
     * @return the book slugs actually kept
     */
    private fun trimToBooks(mode: LegacyMode, books: Set<String>, destination: File): Set<String> {
        val source = tempFiles.createTempFile("${bundledNameFor(mode)}_full", ".zip")
            .also(File::deleteOnExit)
        try {
            bundledContent.readBlocking(bundledPathFor(mode)).inputStream().use { input ->
                source.outputStream().use { output -> input.copyTo(output) }
            }
            val kept = mutableSetOf<String>()
            ZipFile(source).use { zin ->
                ZipOutputStream(destination.outputStream().buffered()).use { out ->
                    zin.entries().asSequence().forEach { entry ->
                        val slug = USFM_NAME.find(entry.name)?.groupValues?.get(1)?.lowercase()
                        // Everything that is not a dropped book — LICENSE, the manifest,
                        // incidental docs — is kept, so the container stays valid.
                        if (slug != null && slug !in books) return@forEach
                        if (slug != null) kept += slug

                        val bytes = zin.getInputStream(entry).use { it.readBytes() }
                        val body = if (entry.name.endsWith("manifest.yaml")) {
                            trimManifest(bytes.decodeToString(), books).encodeToByteArray()
                        } else {
                            bytes
                        }
                        out.putNextEntry(ZipEntry(entry.name).apply { time = entry.time })
                        if (!entry.isDirectory) out.write(body)
                        out.closeEntry()
                    }
                }
            }
            return kept
        } finally {
            runCatching { source.delete() }
        }
    }

    companion object {
        const val LANGUAGE = "en"

        /**
         * The identifier both texts carry.
         *
         * A legacy `versions.slug` labelled the translation being made rather than a source text, so
         * it selects nothing here: `ulb`, `udb` and `reg` projects for one book and target language
         * converge on a single derived project per mode and their takes merge.
         */
        const val IDENTIFIER = "ulb"

        /** Verse-by-verse units, for a legacy verse-mode project. */
        const val VERSE_MODE_VERSION = "12-verse"

        /** Chunk units as bridged verses, for a legacy chunk-mode project. */
        const val CHUNK_MODE_VERSION = "12-chunk"

        val VERSIONS = listOf(VERSE_MODE_VERSION, CHUNK_MODE_VERSION)

        /** `en_ulb/13-1CH.usfm` -> `1ch`. */
        private val USFM_NAME = Regex("""(?:^|/)\d+-(\w+)\.usfm$""")
        private val PROJECT_ID = Regex("""^\s*identifier:\s*'(\w+)'\s*$""")

        /** The dublin_core version carrying [mode]'s units. */
        fun versionFor(mode: LegacyMode): String = when (mode) {
            LegacyMode.CHUNK -> CHUNK_MODE_VERSION
            LegacyMode.VERSE -> VERSE_MODE_VERSION
        }

        fun bundledNameFor(mode: LegacyMode): String = when (mode) {
            LegacyMode.CHUNK -> "en_ulb_chunk"
            LegacyMode.VERSE -> "en_ulb_verse"
        }

        fun bundledPathFor(mode: LegacyMode) = "files/legacy/${bundledNameFor(mode)}.zip"

        /**
         * Whether [metadata] is the mode source for [version]. All three of identifier, version and
         * language are compared, since the plain `ulb` shares the other two.
         */
        fun matchesModeSource(metadata: ResourceMetadata?, version: String): Boolean =
            metadata != null &&
                    metadata.identifier == IDENTIFIER &&
                    metadata.version == version &&
                    metadata.language.slug == LANGUAGE

        /**
         * Keeps only the `projects:` entries whose `identifier` is in [books].
         *
         * `projects:` is the manifest's last section and each entry is a `  -` block, so a block
         * scan suffices; a YAML round-trip would reformat the whole file.
         */
        internal fun trimManifest(manifest: String, books: Set<String>): String {
            val lines = manifest.split("\n")
            val header = lines.indexOfFirst { it.trimEnd() == "projects:" }
            if (header < 0) return manifest

            val kept = mutableListOf<String>()
            var block = mutableListOf<String>()
            fun flushBlock() {
                val slug = block.firstNotNullOfOrNull { PROJECT_ID.find(it)?.groupValues?.get(1) }
                if (slug != null && slug in books) kept += block
                block = mutableListOf()
            }
            for (index in (header + 1) until lines.size) {
                val line = lines[index]
                if (line.trimEnd() == "  -") flushBlock()
                block += line
            }
            flushBlock()

            return (lines.take(header + 1) + kept).joinToString("\n")
        }
    }
}
