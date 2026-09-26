package org.bibletranslationtools.otter.common.persistence

import org.bibletranslationtools.otter.common.data.primitives.ContainerType
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import java.io.File

/**
 * Where a project's files (takes, chunks, source audio) live under the user data directory.
 *
 * Projects created since per-book source pinning live at
 * `<target creator>/<source language>_<source identifier>/<target language>_<target identifier>/<book>`.
 * That has no version and no source edition in it, so the folder stays put when the book moves to
 * another edition of its source, and its takes' stored paths stay valid.
 *
 * Projects created before that live at
 * `<target creator>/<source creator>/<source language>_<source identifier>/v<target version>/<target language>/<book>`.
 * They stay there: take paths are stored as absolute paths, and every access so far has created
 * that folder, so an existing project always has it on disk. Whichever layout's folder exists is
 * used; a project that has neither gets the current layout.
 */
object ProjectDirectoryLayout {

    fun resolve(userData: File, source: ResourceMetadata, target: ResourceMetadata?, bookSlug: String): File {
        val legacy = userData.resolveAll(legacyPath(source, target, bookSlug))
        val directory = if (legacy.isDirectory) legacy else userData.resolveAll(currentPath(source, target, bookSlug))
        directory.mkdirs()
        return directory
    }

    fun currentPath(source: ResourceMetadata, target: ResourceMetadata?, bookSlug: String): List<String> = listOf(
        targetCreator(source, target),
        "${source.language.slug}_${source.identifier}",
        target?.let { "${it.language.slug}_${it.identifier}" } ?: "no_language",
        bookSlug
    )

    fun legacyPath(source: ResourceMetadata, target: ResourceMetadata?, bookSlug: String): List<String> = listOf(
        targetCreator(source, target),
        source.creator,
        "${source.language.slug}_${source.identifier}",
        "v${target?.version ?: "-none"}",
        target?.language?.slug ?: "no_language",
        bookSlug
    )

    /** Audio for a help (notes, questions) is kept under the source's creator. */
    private fun targetCreator(source: ResourceMetadata, target: ResourceMetadata?): String = when {
        target?.type == ContainerType.Help -> source.creator
        target?.creator != null -> target.creator
        else -> "."
    }

    private fun File.resolveAll(components: List<String>): File = components.fold(this, File::resolve)
}
