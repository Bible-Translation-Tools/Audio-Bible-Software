package org.bibletranslationtools.otter.common.persistence

import android.content.Context
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.api.io.zip.IFileReader
import org.bibletranslationtools.otter.common.api.io.zip.IFileWriter
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.wycliffeassociates.resourcecontainer.ResourceContainer
import java.io.File
import javax.inject.Inject

class AndroidDirectoryProvider @Inject constructor (val context: Context): IDirectoryProvider {
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
    ): File {
        TODO("Not yet implemented")
    }

    override fun getProjectDirectory(
        source: ResourceMetadata,
        target: ResourceMetadata?,
        bookSlug: String
    ): File {
        TODO("Not yet implemented")
    }

    override fun getProjectAudioDirectory(
        source: ResourceMetadata,
        target: ResourceMetadata?,
        book: Collection
    ): File {
        TODO("Not yet implemented")
    }

    override fun getProjectAudioDirectory(
        source: ResourceMetadata,
        target: ResourceMetadata?,
        bookSlug: String
    ): File {
        TODO("Not yet implemented")
    }

    override fun getProjectSourceDirectory(
        source: ResourceMetadata,
        target: ResourceMetadata?,
        book: Collection
    ): File {
        TODO("Not yet implemented")
    }

    override fun getProjectSourceDirectory(
        source: ResourceMetadata,
        target: ResourceMetadata?,
        bookSlug: String
    ): File {
        TODO("Not yet implemented")
    }

    override fun getProjectSourceAudioDirectory(
        source: ResourceMetadata,
        target: ResourceMetadata?,
        bookSlug: String
    ): File {
        TODO("Not yet implemented")
    }

    override fun getSourceContainerDirectory(container: ResourceContainer): File {
        TODO("Not yet implemented")
    }

    override fun getSourceContainerDirectory(metadata: ResourceMetadata): File {
        TODO("Not yet implemented")
    }

    override fun getDerivedContainerDirectory(
        metadata: ResourceMetadata,
        source: ResourceMetadata
    ): File {
        TODO("Not yet implemented")
    }

    override fun newFileWriter(file: File): IFileWriter {
        TODO("Not yet implemented")
    }

    override fun newFileReader(file: File): IFileReader {
        TODO("Not yet implemented")
    }

    override fun createTempFile(prefix: String, suffix: String?): File {
        TODO("Not yet implemented")
    }

    override fun cleanTempDirectory() {
        TODO("Not yet implemented")
    }

    override fun openInFileManager(path: String) {
        TODO("Not yet implemented")
    }

    override val databaseDirectory: File
        get() = context.getDatabasePath("tr.db").parentFile!!
    override val resourceContainerDirectory: File
        get() = TODO("Not yet implemented")
    override val internalSourceRCDirectory: File
        get() = TODO("Not yet implemented")
    override val userProfileImageDirectory: File
        get() = TODO("Not yet implemented")
    override val userProfileAudioDirectory: File
        get() = TODO("Not yet implemented")
    override val audioPluginDirectory: File
        get() = TODO("Not yet implemented")
    override val versificationDirectory: File
        get() = TODO("Not yet implemented")
    override val logsDirectory: File
        get() = TODO("Not yet implemented")
    override val cacheDirectory: File
        get() = TODO("Not yet implemented")
    override val tempDirectory: File
        get() = TODO("Not yet implemented")
}