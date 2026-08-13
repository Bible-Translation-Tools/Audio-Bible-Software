package org.bibletranslationtools.otter.common.io.zip

import org.bibletranslationtools.otter.common.api.io.zip.IFileWriter
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * An Android-compatible implementation of [IFileWriter] — the write-side counterpart to
 * [AndroidZipFileReader].
 *
 * [NioZipFileWriter] cannot run on Android. It opens the archive through
 * `FileSystems.newFileSystem("jar:file:...")`, and Android has never shipped a zip
 * FileSystemProvider at any API level. Core library desugaring does not close the gap either:
 * desugar_jdk_libs_nio installs only the default (file) provider, and its `installedProviders()`
 * performs no ServiceLoader lookup, so one cannot be registered from outside. The call throws
 * ProviderNotFoundException. This is independent of minSdk — it was equally broken at 26.
 *
 * Writes are staged in a temporary directory and the archive is repacked once, on [close]. That
 * indirection is necessary rather than merely convenient: [IFileWriter] lets callers write to
 * arbitrary paths in arbitrary order and reopen paths they have already written, while
 * ZipOutputStream is strictly sequential and append-only.
 *
 * Entries already present in [zipFile] are preserved unless a staged write replaces them. Callers
 * depend on this — BackupProjectExporter populates the archive via `initializeResourceContainerInFile`
 * and `setContributorInfo` *before* opening this writer, and would otherwise lose the manifest.
 *
 * Note the cost: [close] rewrites the whole archive, so peak disk use is roughly the size of the
 * export twice over, plus the staged files. NioZipFileWriter (which passes `useTempFile = true`)
 * has a comparable profile, so this is not a regression, but it is worth knowing for large backups.
 */
class AndroidZipFileWriter(
    private val zipFile: File
) : IFileWriter {

    /** Scratch tree holding pending writes until [close] folds them into the archive. */
    private val staging: File = File.createTempFile("otter-zip-write", "").apply {
        delete()
        mkdirs()
    }

    private var closed = false

    override fun bufferedWriter(filepath: String): BufferedWriter = stagedFile(filepath).bufferedWriter()

    override fun outputStream(filepath: String): OutputStream = stagedFile(filepath).outputStream()

    override fun copyDirectory(source: File, destination: String, filter: (String) -> Boolean) {
        if (!source.isDirectory) return

        source.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                // Nio's copyDirectoryTo hands the filter a path relative to the source root, using
                // '/' on Android. Keep that contract — callers pass take-filename predicates.
                val relativePath = file.relativeTo(source).invariantSeparatorsPath
                if (filter(relativePath)) {
                    file.copyTo(stagedFile("$destination/$relativePath"), overwrite = true)
                }
            }
    }

    override fun copyFile(source: File, destination: String) {
        // `destination` is a DIRECTORY here, not the target path: Nio's copyFileTo resolves the
        // source's own filename against it. Callers pass RcConstants.SOURCE_DIR and friends.
        if (!source.isFile) return
        source.copyTo(stagedFile("$destination/${source.name}"), overwrite = true)
    }

    /**
     * Repacks the archive and discards the staging tree.
     *
     * Idempotent by necessity: SourceProjectExporter closes the writer on the success path and
     * again from doOnError.
     */
    override fun close() {
        if (closed) return
        closed = true
        try {
            repack()
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun stagedFile(filepath: String): File {
        val normalized = normalizePath(filepath)
        require(normalized.isNotEmpty()) { "Cannot write to an empty path in ${zipFile.name}" }
        return File(staging, normalized).also { it.parentFile?.mkdirs() }
    }

    private fun repack() {
        val staged = staging.walkTopDown()
            .filter { it.isFile }
            .associateBy { it.relativeTo(staging).invariantSeparatorsPath }

        // Nothing was written and the archive already exists: rewriting it would only risk
        // corrupting a good file for no gain.
        if (staged.isEmpty() && zipFile.isFile) return

        zipFile.parentFile?.mkdirs()
        // Alongside the target rather than in staging, so the swap below is a rename within one
        // filesystem rather than a copy across the tmpfs boundary.
        val scratch = File(zipFile.absolutePath + ".repacking")
        scratch.delete()

        val written = mutableSetOf<String>()
        ZipOutputStream(scratch.outputStream().buffered()).use { out ->
            // Staged writes go in first so that `written` makes them win over any stale entry of
            // the same name still sitting in the original archive.
            staged.forEach { (entryName, file) ->
                writeParentDirectoryEntries(out, entryName, written)
                if (written.add(entryName)) {
                    out.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(out) }
                    out.closeEntry()
                }
            }

            if (zipFile.isFile) {
                ZipFile(zipFile).use { existing ->
                    existing.entries().asSequence().forEach { entry ->
                        if (!written.add(entry.name)) return@forEach
                        out.putNextEntry(
                            ZipEntry(entry.name).also { if (entry.time != -1L) it.time = entry.time }
                        )
                        if (!entry.isDirectory) {
                            existing.getInputStream(entry).use { it.copyTo(out) }
                        }
                        out.closeEntry()
                    }
                }
            }
        }

        swapIn(scratch)
    }

    /**
     * Emits explicit directory entries for each ancestor of [entryName]. The zip format does not
     * require them, but zipfs writes them and [AndroidZipFileReader.exists] probes for a trailing
     * slash, so omitting them would make directories that used to resolve stop resolving.
     */
    private fun writeParentDirectoryEntries(
        out: ZipOutputStream,
        entryName: String,
        written: MutableSet<String>
    ) {
        var separator = entryName.indexOf('/')
        while (separator >= 0) {
            val directory = entryName.substring(0, separator + 1)
            if (written.add(directory)) {
                out.putNextEntry(ZipEntry(directory))
                out.closeEntry()
            }
            separator = entryName.indexOf('/', separator + 1)
        }
    }

    private fun swapIn(scratch: File) {
        if (zipFile.exists() && !zipFile.delete()) {
            scratch.delete()
            throw IOException("Unable to replace ${zipFile.absolutePath} with the repacked archive")
        }
        if (!scratch.renameTo(zipFile)) {
            scratch.copyTo(zipFile, overwrite = true)
            scratch.delete()
        }
    }

    // Zip entries always use forward slashes, regardless of platform. Matches AndroidZipFileReader.
    private fun normalizePath(path: String): String = path.replace("\\", "/").trim('/')
}
