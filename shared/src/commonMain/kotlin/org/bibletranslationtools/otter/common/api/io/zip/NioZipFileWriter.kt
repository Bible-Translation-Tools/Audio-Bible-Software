package org.bibletranslationtools.otter.common.api.io.zip

import org.bibletranslationtools.otter.common.api.io.utils.copyDirectoryTo
import org.bibletranslationtools.otter.common.api.io.utils.copyFileTo
import org.bibletranslationtools.otter.common.api.io.utils.createParentDirectories
import org.bibletranslationtools.otter.common.api.io.utils.jarUri
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStream
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files

class NioZipFileWriter(
    zipFile: File
) : IFileWriter {

    // useTempFile set to true to reduce memory usage when writing large zip files
    private val fileSystem: FileSystem = FileSystems.newFileSystem(
        zipFile.jarUri(),
        mapOf("create" to "true", "useTempFile" to true)
    )

    override fun close() = fileSystem.close()

    override fun bufferedWriter(filepath: String): BufferedWriter {
        val path = fileSystem.getPath(filepath)
        path.createParentDirectories()
        return Files.newBufferedWriter(path)
    }

    override fun outputStream(filepath: String): OutputStream {
        val path = fileSystem.getPath(filepath)
        path.createParentDirectories()
        return Files.newOutputStream(path)
    }

    override fun copyDirectory(source: File, destination: String, filter: (String) -> Boolean) {
        val sourcePath = source.toPath()
        val destPath = fileSystem.getPath(destination)
        sourcePath.copyDirectoryTo(destPath, filter)
    }

    override fun copyFile(source: File, destination: String) {
        val sourcePath = source.toPath()
        val destPath = fileSystem.getPath(destination)
        if (Files.isRegularFile(sourcePath)) {
            sourcePath.copyFileTo(destPath)
        }
    }
}
