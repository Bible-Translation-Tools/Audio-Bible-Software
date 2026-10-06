package org.bibletranslationtools.otter.common.domain.project

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.OTTER_JSON
import org.bibletranslationtools.otter.common.api.persistence.IFileIOFactory
import org.bibletranslationtools.otter.common.api.persistence.repositories.ICollectionRepository
import org.bibletranslationtools.otter.common.audio.AudioFileFormat
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.ContainerType
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.data.primitives.SerializableProjectMode
import org.bibletranslationtools.otter.common.domain.content.FileNamer
import org.bibletranslationtools.otter.common.domain.resourcecontainer.RcConstants
import org.bibletranslationtools.otter.common.domain.resourcecontainer.projectimportexport.buildManifest
import org.wycliffeassociates.resourcecontainer.ResourceContainer
import java.io.File

/**
 * Writes a backup of one book holding chapter takes recorded somewhere else. It has the files a
 * backup made here has, so it restores through the ordinary import like any other backup.
 *
 * The book is on `edition`. The backup embeds `editionFile` (the container `edition` was installed
 * from) where Orature expects a backup's source, and names the edition in its edition record, so
 * the restore finds it by its content. Each chapter's takes are numbered in the order given and
 * named the way this app names chapter takes.
 */
class WriteChapterTakesBackup(
    private val fileIO: IFileIOFactory,
    private val collectionRepository: ICollectionRepository,
    private val backupEditions: BackupEditions
) {
    /** [file]'s audio, as a take of chapter [chapter]; at most one take of a chapter is [selected]. */
    data class ChapterTake(val chapter: Int, val file: File, val selected: Boolean)

    /** Writes the backup to [destination], replacing it. */
    suspend fun execute(
        destination: File,
        edition: ResourceMetadata,
        editionFile: File,
        book: String,
        targetLanguage: Language,
        mode: ProjectMode,
        contributors: List<String>,
        takes: List<ChapterTake>
    ): Unit = withContext(Dispatchers.IO) {
        val sourceBook = collectionRepository.getSourceProjectsSuspend()
            .firstOrNull { it.resourceContainer?.id == edition.id && it.slug == book }
            ?: throw IllegalArgumentException("${edition.language.slug}_${edition.identifier} v${edition.version} has no book $book")
        val chapters = collectionRepository.getChildrenSuspend(sourceBook).associateBy { it.sort }
        takes.groupBy { it.chapter }.forEach { (chapter, chapterTakes) ->
            require(chapter in chapters) { "$book has no chapter $chapter" }
            require(chapterTakes.count { it.selected } <= 1) { "$book $chapter has more than one selected take" }
        }

        destination.delete()
        destination.parentFile?.mkdirs()
        writeManifest(destination, edition, sourceBook, targetLanguage, contributors)

        fileIO.newFileWriter(destination).use { writer ->
            val selected = mutableListOf<String>()
            takes.groupBy { it.chapter }.forEach { (number, chapterTakes) ->
                val namer = chapterNamer(chapters.getValue(number), chapters.size, book, edition, targetLanguage)
                chapterTakes.forEachIndexed { index, take ->
                    val name = namer.generateName(index + 1, AudioFileFormat.of(take.file.extension))
                    // Relative to the take folder, as a backup's selected-takes file names them.
                    val path = "${namer.formatChapterNumber()}/$name"
                    writer.outputStream("${RcConstants.TAKE_DIR}/$path").use { out ->
                        take.file.inputStream().use { it.copyTo(out) }
                    }
                    if (take.selected) selected += path
                }
            }
            writer.bufferedWriter(RcConstants.SELECTED_TAKES_FILE).use { out -> selected.forEach { out.appendLine(it) } }
            writer.bufferedWriter(RcConstants.PROJECT_MODE_FILE).use {
                it.write(OTTER_JSON.encodeToString(SerializableProjectMode.serializer(), SerializableProjectMode(mode)))
            }

            val embedded = "${RcConstants.SOURCE_DIR}/${edition.language.slug}_${edition.identifier}.zip"
            writer.outputStream(embedded).use { out -> editionFile.inputStream().use { it.copyTo(out) } }
            backupEditions.write(writer, backupEditions.recordFor(book, edition, embedded))
        }
    }

    /** The manifest a project's own container has: the target language, on [edition], for [sourceBook]. */
    private fun writeManifest(
        destination: File,
        edition: ResourceMetadata,
        sourceBook: Collection,
        targetLanguage: Language,
        contributors: List<String>
    ) {
        val target = edition.copy(
            language = targetLanguage,
            creator = RcConstants.DERIVED_CREATOR,
            // Sources can be bundles, but a project's container is a book.
            type = if (edition.type == ContainerType.Bundle) ContainerType.Book else edition.type
        )
        ResourceContainer
            .create(destination) {
                manifest = buildManifest(target, edition, sourceBook, "./${RcConstants.MEDIA_DIR}").apply {
                    dublinCore.contributor = contributors.toMutableList()
                }
            }
            .use { it.write() }
    }

    /** Names chapter takes as a project on [edition] in [targetLanguage] does. */
    private fun chapterNamer(
        chapter: Collection,
        chapterCount: Int,
        book: String,
        edition: ResourceMetadata,
        targetLanguage: Language
    ) = FileNamer(
        contentType = ContentType.META,
        languageSlug = targetLanguage.slug,
        bookSlug = book,
        rcSlug = if (edition.language.slug == targetLanguage.slug) edition.identifier else FileNamer.DEFAULT_RC_SLUG,
        chunkCount = 0,
        chapterCount = chapterCount.toLong(),
        chapterTitle = chapter.titleKey,
        chapterSort = chapter.sort
    )
}
