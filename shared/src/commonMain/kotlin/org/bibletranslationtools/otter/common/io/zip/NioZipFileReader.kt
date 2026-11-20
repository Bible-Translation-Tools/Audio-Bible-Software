package org.bibletranslationtools.otter.common.io.zip

import org.bibletranslationtools.otter.common.api.io.zip.IFileReader
import io.reactivex.Observable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.io.utils.copyDirectoryFilesTo
import org.bibletranslationtools.otter.common.io.utils.copyDirectoryTo
import org.bibletranslationtools.otter.common.io.utils.jarUri
import java.io.File
import java.io.InputStream
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import kotlin.streams.asSequence

class NioZipFileReader(
    zipFile: File
) : IFileReader {
    private val fileSystem: FileSystem = FileSystems.newFileSystem(zipFile.jarUri(), mapOf("create" to "false"))

    override fun close() = fileSystem.close()

    override fun exists(filepath: String) = Files.exists(fileSystem.getPath(filepath))

    override fun list(directory: String): Sequence<String> {
        return Files
            .list(fileSystem.getPath(directory))
            .map { it.toString() }
            .asSequence()
    }

    override fun bufferedReader(filepath: String) = Files.newBufferedReader(fileSystem.getPath(filepath))!!

    override fun stream(filepath: String): InputStream = Files.newInputStream(fileSystem.getPath(filepath))

    override fun copyDirectory(
        source: String,
        destinationDirectory: File,
        filter: (String) -> Boolean
    ): Observable<String> = fileSystem.getPath(source).copyDirectoryTo(destinationDirectory.toPath(), filter)

    override suspend fun copyDirectorySuspend(
        source: String,
        destinationDirectory: File,
        filter: (String) -> Boolean
    ): List<String> = withContext(Dispatchers.IO) {
        fileSystem.getPath(source).copyDirectoryFilesTo(destinationDirectory.toPath(), filter)
    }
}
