package org.bibletranslationtools.otter.common.api.io.zip

import io.reactivex.Observable
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import java.util.zip.ZipEntry

/**
 * An Android-compatible implementation of IFileReader that works with ZIP/JAR files.
 * Replaces NioDirectoryFileReader when the source is a compressed archive.
 */
class AndroidZipFileReader(
    private val zipFileSource: File
) : IFileReader {

    // We keep the ZipFile open for the duration of this reader's life.
    // It must be closed by calling .close() when done.
    private val zipFile = ZipFile(zipFileSource)

    override fun bufferedReader(filepath: String): BufferedReader {
        val entry = getEntryOrThrow(filepath)
        return zipFile.getInputStream(entry).reader().buffered()
    }

    override fun stream(filepath: String): InputStream {
        val entry = getEntryOrThrow(filepath)
        return zipFile.getInputStream(entry)
    }

    override fun exists(filepath: String): Boolean {
        // We normalize to ensure we match the internal ZIP path format (forward slashes)
        val normalizedPath = normalizePath(filepath)
        // Check for exact file match or directory match (directories in zips often end in /)
        return zipFile.getEntry(normalizedPath) != null ||
                zipFile.getEntry("$normalizedPath/") != null
    }

    override fun list(directory: String): Sequence<String> {
        val normalizedDir = normalizePath(directory).let { if (it.endsWith("/")) it else "$it/" }

        return zipFile.entries().asSequence()
            .map { it.name }
            .filter { it.startsWith(normalizedDir) && it != normalizedDir }
            .map { fullPath ->
                // Remove the parent directory prefix
                val relativePath = fullPath.removePrefix(normalizedDir)
                // If there is still a slash, it's a subdirectory; strictly take the immediate child
                relativePath.substringBefore("/")
            }
            .distinct() // Ensure we don't list the same subdirectory multiple times
    }

    override fun copyDirectory(
        source: String,
        destinationDirectory: File,
        filter: (String) -> Boolean
    ): Observable<String> {
        return Observable.create { emitter ->
            try {
                val normalizedSource = normalizePath(source).let { if (it.endsWith("/")) it else "$it/" }

                // Iterate over all entries in the zip
                val entries = zipFile.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()

//                    // 1. Check if the entry is inside the source folder
//                    if (entry.name.startsWith(normalizedSource)) {

                        // 2. Determine relative path (e.g., "config/data.xml" inside "assets/")
                        val relativePath = entry.name.removePrefix(normalizedSource)
                        if (relativePath.isEmpty()) continue // Skip the root folder itself

                        // 3. Apply the user-provided filter
                        if (filter(relativePath)) {
                            val outFile = File(destinationDirectory, relativePath)

                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                // Copy stream to file
                                zipFile.getInputStream(entry).use { input ->
                                    outFile.outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                }
                                emitter.onNext(outFile.absolutePath)
                            }
                        }

                }
                emitter.onComplete()
            } catch (e: Exception) {
                emitter.onError(e)
            }
        }
    }

    override fun close() {
        zipFile.close()
    }

    private fun getEntryOrThrow(filepath: String): ZipEntry {
        val normalized = normalizePath(filepath)
        return zipFile.getEntry(normalized)
            ?: throw java.io.FileNotFoundException("Entry '$filepath' not found in ${zipFileSource.absolutePath}")
    }

    // Zip files always use forward slashes, regardless of OS
    private fun normalizePath(path: String): String {
        return path.replace("\\", "/").trim('/')
    }
}