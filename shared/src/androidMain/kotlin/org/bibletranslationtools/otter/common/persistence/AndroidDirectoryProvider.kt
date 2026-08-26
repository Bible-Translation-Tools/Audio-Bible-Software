package org.bibletranslationtools.otter.common.persistence

import android.content.Context
import org.bibletranslationtools.otter.common.io.zip.AndroidZipFileReader
import org.bibletranslationtools.otter.common.io.zip.AndroidZipFileWriter
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.api.io.zip.IFileReader
import org.bibletranslationtools.otter.common.api.io.zip.IFileWriter
import org.bibletranslationtools.otter.common.io.zip.NioDirectoryFileReader
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.data.OratureFileFormat
import org.bibletranslationtools.otter.common.data.primitives.ContainerType
import org.wycliffeassociates.resourcecontainer.ResourceContainer
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.FileSystems

class AndroidDirectoryProvider(val context: Context): IDirectoryProvider {

    private val pathSeparator = FileSystems.getDefault().separator

    override fun getUserDataDirectory(appendedPath: String): File {
        return File(context.filesDir, appendedPath)
    }

    override fun getAppDataDirectory(appendedPath: String): File {
        return File(context.filesDir, appendedPath)
    }

    override fun getProjectDirectory(
        source: ResourceMetadata,
        target: ResourceMetadata?,
        book: Collection
    ) = getProjectDirectory(source, target, book.slug)

    override fun getProjectDirectory(
        source: ResourceMetadata,
        target: ResourceMetadata?,
        bookSlug: String
    ): File {
        // Audio is being stored in the source creator directory for resources
        val targetCreator = when {
            target?.type == ContainerType.Help -> source.creator
            target?.creator != null -> target.creator
            else -> "."
        }
        val appendedPath = listOf(
            targetCreator,
            source.creator,
            "${source.language.slug}_${source.identifier}",
            "v${target?.version ?: "-none"}",
            target?.language?.slug ?: "no_language",
            bookSlug
        ).joinToString(pathSeparator)
        val path = getUserDataDirectory(appendedPath)
        path.mkdirs()
        return path
    }

    override fun getProjectAudioDirectory(
        source: ResourceMetadata,
        target: ResourceMetadata?,
        book: Collection
    ) = getProjectAudioDirectory(source, target, book.slug)

    override fun getProjectAudioDirectory(
        source: ResourceMetadata,
        target: ResourceMetadata?,
        bookSlug: String
    ): File {
        val path = getProjectDirectory(source, target, bookSlug)
            .resolve(".apps")
            .resolve("orature")
            .resolve("takes")
        path.mkdirs()
        return path
    }

    override fun getProjectSourceDirectory(
        source: ResourceMetadata,
        target: ResourceMetadata?,
        book: Collection
    ) = getProjectSourceDirectory(source, target, book.slug)

    override fun getProjectSourceDirectory(
        source: ResourceMetadata,
        target: ResourceMetadata?,
        bookSlug: String
    ): File {
        val path = getProjectDirectory(source, target, bookSlug)
            .resolve(".apps")
            .resolve("orature")
            .resolve("source")
        path.mkdirs()
        return path
    }

    override fun getProjectSourceAudioDirectory(
        source: ResourceMetadata,
        target: ResourceMetadata?,
        bookSlug: String
    ): File {
        val path = getProjectSourceDirectory(source, target, bookSlug)
            .resolve("audio")
        path.mkdirs()
        return path
    }

    override fun getSourceContainerDirectory(container: ResourceContainer): File {
        val dublinCore = container.manifest.dublinCore
        container.close()
        val appendedPath = listOf(
            dublinCore.creator,
            "${dublinCore.language.identifier}_${dublinCore.identifier}",
            "v${dublinCore.version}"
        ).joinToString(pathSeparator)
        val path = internalSourceRCDirectory.resolve(appendedPath)
        path.mkdirs()
        return path
    }

    override fun getSourceContainerDirectory(metadata: ResourceMetadata): File {
        return listOf(
            metadata.creator,
            "${metadata.language.slug}_${metadata.identifier}",
            "v${metadata.version}"
        )
            .fold(internalSourceRCDirectory, File::resolve)
            .apply { mkdirs() }
    }

    override fun getDerivedContainerDirectory(metadata: ResourceMetadata, source: ResourceMetadata): File {
        val appendedPath = listOf(
            "der",
            metadata.creator,
            source.creator,
            "${source.language.slug}_${source.identifier}",
            "v${metadata.version}",
            metadata.language.slug
        ).joinToString(pathSeparator)
        val path = resourceContainerDirectory.resolve(appendedPath)
        path.mkdirs()
        return path
    }

    // Not NioZipFileWriter: Android has no zip FileSystemProvider at any API level, so its
    // FileSystems.newFileSystem("jar:file:...") throws ProviderNotFoundException. See
    // AndroidZipFileWriter. The reader below has been on the java.util.zip path already.
    override fun newFileWriter(file: File): IFileWriter = AndroidZipFileWriter(file)

    override fun newFileReader(file: File): IFileReader {
        if (!file.exists()) throw FileNotFoundException("File ${file.absolutePath} does not exist.")

        return when {
            file.isDirectory -> NioDirectoryFileReader(file)
            file.isFile && file.extension in OratureFileFormat.extensionList
                -> AndroidZipFileReader(file)
            else -> {
                if (magicNumberIsZip(file)) {
                    AndroidZipFileReader(file)
                } else {
                    throw IllegalArgumentException("File type not supported, file name ${file.name}, extension ${file.extension}")
                }
            }
        }
    }

    /**
     * Examines the beginning of a file to see if its magic number matches the magic number
     * for zip files. This will help attempt to use zip files that are stripped of file
     * extensions.
     */
    private fun magicNumberIsZip(file: File): Boolean {
        if (file.isFile) {
            try {
                val bytes = ByteArray(4)
                file.inputStream().read(bytes)
                val numberMatches = booleanArrayOf(
                    bytes[0] == 'P'.code.toByte(),
                    bytes[1] == 'K'.code.toByte(),
                    bytes[2].toInt() == 0b0011,
                    bytes[3].toInt() == 0b0100
                ).all { it }
                return numberMatches
            } catch (e: IOException) {
                return false
            }
        } else {
            return false
        }
    }

    override fun createTempFile(prefix: String, suffix: String?): File {
        tempDirectory.mkdirs()
        return File.createTempFile(prefix, suffix, tempDirectory)
    }

    override fun cleanTempDirectory() {
        deleteRecursively(tempDirectory)
    }

    override val databaseDirectory: File
        get() = context.getDatabasePath("tr.db").parentFile!!

    override val resourceContainerDirectory: File
        get() = getAppDataDirectory("rc")

    override val internalSourceRCDirectory: File
        get() = resourceContainerDirectory.resolve("src")

    override val userProfileAudioDirectory: File
        get() = TODO()

    override val userProfileImageDirectory: File
        get() = TODO()

    override val audioPluginDirectory: File
        get() = getAppDataDirectory("plugins")

    override val versificationDirectory: File
        get() = getAppDataDirectory("versification")

    override val logsDirectory: File
        get() = getAppDataDirectory("logs")

    override val cacheDirectory: File
        get() = context.cacheDir // Use context.cacheDir directly

    override val tempDirectory: File
        get() = getAppDataDirectory("temp")

    private fun deleteRecursively(dir: File) {
        dir.listFiles()?.forEach {
            if (it.isDirectory) {
                deleteRecursively(it)
            }
            it.delete()
        }
    }
}