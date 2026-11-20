package org.bibletranslationtools.otter.common.io.zip

import org.bibletranslationtools.otter.common.api.io.zip.IFileReader
import java.io.File
import java.io.InputStream
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence
import org.bibletranslationtools.otter.common.io.utils.copyDirectoryFilesTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NioDirectoryFileReader(
    private val dir: File
) : IFileReader {
    private val fileSystem: FileSystem = FileSystems.getDefault()

    override fun bufferedReader(filepath: String) = Files.newBufferedReader(getAbsolutePath(filepath))!!

    override fun stream(filepath: String): InputStream = Files.newInputStream(getAbsolutePath(filepath))

    override fun exists(filepath: String) = Files.exists(getAbsolutePath(filepath))

    override suspend fun copyDirectorySuspend(
        source: String,
        destinationDirectory: File,
        filter: (String) -> Boolean
    ): List<String> = withContext(Dispatchers.IO) {
        getAbsolutePath(source).copyDirectoryFilesTo(destinationDirectory.toPath(), filter)
    }

    override fun list(directory: String): Sequence<String> {
        return Files
            .list(getAbsolutePath(directory))
            .map { dir.toPath().relativize(it).toString() }
            .asSequence()
    }

    override fun close() {
        // No need to close this file system
        // Default file system cannot be closed
    }

    private fun getAbsolutePath(path: String): Path {
        return fileSystem.getPath(dir.absolutePath, path)
    }
}
