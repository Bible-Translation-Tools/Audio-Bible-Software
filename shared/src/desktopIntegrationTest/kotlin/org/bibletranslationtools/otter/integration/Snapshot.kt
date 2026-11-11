package org.bibletranslationtools.otter.integration

import java.io.File
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The end state of an [IntegrationEnvironment]: every table's rows and every file, normalized so
 * that two runs of the same scenario give the same text.
 *
 * This is how the RxJava migration shows that a ported import, export, create or delete leaves the
 * same things behind: the snapshot recorded from the Rx code is committed under
 * `desktopIntegrationTest/resources/snapshots/`, and the scenario must keep producing it.
 *
 * What is committed is the [digest]: a row count and a hash per table, a hash per file. It says
 * which table or file changed. To see how, run the scenario before and after the change and diff
 * the [full] dumps, which every run writes to `shared/build/snapshots/`.
 *
 * Normalized away: the temp directory, per-OS app directories, dates and times, and run-specific
 * directory names. A zip is compared entry by entry, since its bytes carry timestamps.
 */
class Snapshot(val digest: String, val full: String) {

    companion object {
        private val textExtensions = setOf("yaml", "yml", "json", "txt", "usfm", "md", "vtt", "cue")
        private val dateTime = Regex("""\d{4}-\d{2}-\d{2}([T ]\d{2}:\d{2}(:\d{2}(\.\d+)?)?)?""")
        /** A backup's file name stamp (`20261111-1929`), then any long number: temp directory names. */
        private val runSpecific = Regex("""\d{8}-\d{4}|\d{8,}""")

        /**
         * Tables whose rows are inserted in directory-listing order, which differs between file
         * systems: startup inserts one versification row per file that `listFiles()` returns. Their
         * ids follow that order, and nothing refers to them, so these are compared as a set of rows
         * without the id.
         */
        private val insertedInListingOrder = setOf("versification_entity")

        /**
         * @param roots absolute paths to replace by a name, e.g. the app data directory by `<app>`.
         *   Longer paths are replaced first, so a directory inside the temp root keeps its own name.
         */
        fun of(tempRoot: File, database: File, roots: Map<File, String>): Snapshot {
            val replacements = (roots + (tempRoot to "<root>"))
                .flatMap { (dir, name) -> listOf(dir.absolutePath to name, dir.canonicalPath to name) }
                .distinct()
                .sortedByDescending { it.first.length }

            fun normalize(text: String): String {
                var out = text
                replacements.forEach { (path, name) -> out = out.replace(path, name) }
                return out.replace(dateTime, "<date>").replace(runSpecific, "N")
            }

            val digest = StringBuilder()
            val full = StringBuilder()

            digest.appendLine("# tables")
            DriverManager.getConnection("jdbc:sqlite:${database.absolutePath}").use { connection ->
                val tables = connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
                    ).use { rows -> generateSequence { if (rows.next()) rows.getString(1) else null }.toList() }
                }
                tables.forEach { table ->
                    val rows = connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT * FROM $table ORDER BY rowid").use { result ->
                            val columns = result.metaData.columnCount
                            val firstColumn = if (table in insertedInListingOrder) 2 else 1
                            generateSequence {
                                if (!result.next()) null
                                else (firstColumn..columns).joinToString("|") { normalize(result.getString(it) ?: "NULL") }
                            }.toList()
                        }
                    }.let { if (table in insertedInListingOrder) it.sorted() else it }
                    digest.appendLine("$table rows=${rows.size} sha=${sha(rows.joinToString("\n"))}")
                    full.appendLine("## $table")
                    rows.forEach { full.appendLine(it) }
                }
            }

            digest.appendLine("# files")
            val files = tempRoot.walkTopDown()
                .filter { it != tempRoot && !it.name.startsWith(database.name) }
                .filter { it.isFile || it.list().isNullOrEmpty() }
                .map { normalize(it.absolutePath) to it }
                .sortedBy { it.first }
                .toList()
            files.forEach { (path, file) ->
                val line = when {
                    file.isDirectory -> "$path/ (empty)"
                    isZip(file) -> {
                        val entries = zipEntries(file, ::normalize)
                        (listOf("$path (zip, ${entries.size} entries)") + entries.map { "  $it" }).joinToString("\n")
                    }
                    else -> "$path sha=${contentSha(file.name, file.readBytes(), ::normalize)}"
                }
                digest.appendLine(line)
            }
            full.append(digest.substring(digest.indexOf("# files")))
            return Snapshot(digest.toString(), full.toString())
        }

        private val zipMagic = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

        private fun isZip(file: File): Boolean = file.length() >= 4 &&
            file.inputStream().use { it.readNBytes(4) }.contentEquals(zipMagic)

        /** One line per entry, by name. A zip inside the zip is hashed by its own entries, not its bytes. */
        private fun zipEntries(file: File, normalize: (String) -> String): List<String> = ZipFile(file).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory }.sortedBy { it.name }.map { entry ->
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val hash = if (bytes.take(4).toByteArray().contentEquals(zipMagic)) {
                    val inner = File.createTempFile("snapshot-inner", ".zip")
                    try {
                        inner.writeBytes(bytes)
                        sha(zipEntries(inner, normalize).joinToString("\n"))
                    } finally {
                        inner.delete()
                    }
                } else {
                    contentSha(entry.name, bytes, normalize)
                }
                "${normalize(entry.name)} sha=$hash"
            }.toList()
        }

        private fun contentSha(name: String, bytes: ByteArray, normalize: (String) -> String): String =
            if (name.substringAfterLast('.', "").lowercase() in textExtensions) sha(normalize(bytes.decodeToString()))
            else sha(bytes)

        private fun sha(text: String) = sha(text.toByteArray())

        private fun sha(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * Compares with the committed snapshot [name]. With `RECORD_SNAPSHOTS=1` in the environment it
     * writes the committed file instead: do that on the code whose behavior is being pinned, never
     * to make a failing comparison pass.
     */
    fun assertMatches(name: String, repoRoot: File) {
        File(repoRoot, "shared/build/snapshots").apply { mkdirs() }.resolve("$name.full.txt").writeText(full)
        val committed = File(repoRoot, "shared/src/desktopIntegrationTest/resources/snapshots/$name.txt")
        if (System.getenv("RECORD_SNAPSHOTS") == "1") {
            committed.parentFile.mkdirs()
            committed.writeText(digest)
            return
        }
        if (!committed.isFile) fail("no snapshot '$name' is committed; record it with RECORD_SNAPSHOTS=1")
        assertEquals(
            committed.readText(), digest,
            "the end state differs from snapshot '$name'; the full dump is in shared/build/snapshots/$name.full.txt"
        )
    }
}
