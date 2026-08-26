package org.bibletranslationtools.otter.common.io.zip

import org.bibletranslationtools.otter.common.api.io.zip.IFileReader
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

    /**
     * Copies EAGERLY, then returns the results as an already-complete Observable.
     *
     * That laziness distinction is the whole point. NioDirectoryFileReader (via copyDirectoryTo)
     * performs its copy before it returns and hands back a cached Observable, so a caller that
     * never subscribes still gets the files. This used to be `Observable.create { … }`, which does
     * nothing at all until subscribed — and most callers of IFileReader.copyDirectory do not
     * subscribe. On desktop that is invisible; on Android it silently copied nothing, which is how
     * the bundled ULB import ended up failing with "Missing manifest.yaml" against an empty
     * directory.
     *
     * Matching the Nio semantics fixes every one of those call sites at once, rather than leaving
     * the interface with two contradictory contracts.
     */
    override fun copyDirectory(
        source: String,
        destinationDirectory: File,
        filter: (String) -> Boolean
    ): Observable<String> {
        val normalizedSource = normalizePath(source).let { if (it.endsWith("/")) it else "$it/" }
        val copied = mutableListOf<String>()

        for (entry in zipFile.entries()) {
            // Only entries genuinely under `source`. Without this an unrelated entry keeps its
            // full path through removePrefix and gets written into the destination anyway; it is
            // harmless when source is "/" but wrong for the subdirectory copies that
            // ProjectFilesAccessor does (RcConstants.TAKE_DIR and friends).
            if (normalizedSource != "/" && !entry.name.startsWith(normalizedSource)) continue

            val relativePath = entry.name.removePrefix(normalizedSource)
            if (relativePath.isEmpty()) continue // the root folder itself
            if (!filter(relativePath)) continue

            val outFile = File(destinationDirectory, relativePath)
            if (entry.isDirectory) {
                outFile.mkdirs()
            } else {
                outFile.parentFile?.mkdirs()
                zipFile.getInputStream(entry).use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
                copied.add(outFile.absolutePath)
            }
        }

        return Observable.fromIterable(copied).cache()
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