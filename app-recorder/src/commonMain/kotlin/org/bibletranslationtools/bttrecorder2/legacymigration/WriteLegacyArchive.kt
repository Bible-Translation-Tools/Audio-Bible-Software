package org.bibletranslationtools.bttrecorder2.legacymigration

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes an archive of everything the old BTT-Recorder left on this device (M10-1, M10-2), for the
 * user to keep before it is deleted: its whole `translations/` folder (every project, migrated or
 * not, OBS and tQ included, and files no project names), its database, and the migration journal,
 * which says what became of each project. Source-audio archives aren't in it (M10-3).
 *
 * It streams into the destination, so it needs no room on the device for a copy of the recordings.
 */
class WriteLegacyArchive(private val storage: ILegacyStorage, private val journal: MigrationJournal) {

    /** What went into the archive. */
    data class Result(val files: Int, val bytes: Long)

    /** Writes the archive to [destination], and closes it. */
    suspend fun execute(destination: OutputStream): Result = withContext(Dispatchers.IO) {
        var files = 0
        var bytes = 0L
        ZipOutputStream(destination.buffered()).use { zip ->
            // The recordings barely compress; speed matters more on a phone.
            zip.setLevel(Deflater.BEST_SPEED)
            fun add(file: File, name: String) {
                zip.putNextEntry(ZipEntry(name).apply { time = file.lastModified() })
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                files++
                bytes += file.length()
            }
            storage.translationsDir?.takeIf { it.isDirectory }?.let { translations ->
                translations.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { file ->
                    add(file, "$TRANSLATIONS/${file.relativeTo(translations).invariantSeparatorsPath}")
                }
            }
            storage.databaseFile?.let { add(it, "$DATABASES/${it.name}") }
            journal.file.takeIf { it.isFile }?.let { add(it, it.name) }
        }
        Result(files, bytes)
    }

    companion object {
        /** Folders in the archive, named as the old app named them. */
        const val TRANSLATIONS = "translations"
        const val DATABASES = "databases"
    }
}
