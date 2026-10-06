package org.bibletranslationtools.bttrecorder2.legacymigration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Reads the old BTT-Recorder's projects from its database (`translation_projects`, schema version
 * 4) and its recordings (`translations/<lang>/<version>/<book>/<chapter>/<take>.wav`). A platform
 * supplies the two: [openDatabase] opens the database read-only, or returns null when there is
 * none, and [translationsDir] is the recordings' folder.
 *
 * What the old app's data looks like, from its source:
 * - `units.end_verse` is never written: a unit is created with its first take, keyed by its first
 *   verse. A take's range comes from its filename, parsed with its anthology's own regex and
 *   groups (`anthologies.regex`, `anthologies.groups`), and from the JSON in the WAV.
 * - `projects.book_fk` and `projects.mode_fk` are declared TEXT, though they hold ids.
 * - `takes.timestamp` is the file's last-modified time, in epoch milliseconds.
 *
 * It never throws: what it can't read becomes [LegacyReadResult.Unreadable], or a [SkippedRow].
 */
class LegacyProjectReader(
    private val openDatabase: () -> LegacyDatabase?,
    private val translationsDir: () -> File?
) : ILegacyProjectReader {

    private val logger = LoggerFactory.getLogger(LegacyProjectReader::class.java)

    override suspend fun read(): LegacyReadResult = withContext(Dispatchers.IO) {
        try {
            val translations = translationsDir()
            val database = openDatabase()
            if (database == null) {
                val folders = translations?.let(::bookFolders).orEmpty()
                return@withContext if (folders.isEmpty()) LegacyReadResult.NothingInstalled
                else LegacyReadResult.Read(emptyList(), emptyList(), folders)
            }
            database.use { db ->
                translations ?: return@withContext LegacyReadResult.Unreadable("the old app's recordings folder isn't available")
                val version = db.query("PRAGMA user_version").firstOrNull()?.int("user_version")
                if (version != SCHEMA_VERSION) {
                    return@withContext LegacyReadResult.Unreadable("the old app's database is schema version $version, not $SCHEMA_VERSION")
                }
                read(db, translations)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Could not read the old app's projects", e)
            LegacyReadResult.Unreadable(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    private fun read(db: LegacyDatabase, translations: File): LegacyReadResult.Read {
        val skipped = mutableListOf<SkippedRow>()
        val modes = db.query("SELECT slug, name, type, anthology_fk FROM modes")
        val projects = db.query(PROJECTS).mapNotNull { row ->
            projectFrom(db, row, modes, translations, skipped)
        }
        val claimed = projects.map { it.folder.canonicalFile }.toSet()
        val unclaimed = bookFolders(translations).filter { it.canonicalFile !in claimed }
        return LegacyReadResult.Read(projects, skipped, unclaimed)
    }

    private fun projectFrom(
        db: LegacyDatabase,
        row: LegacyRow,
        modes: List<LegacyRow>,
        translations: File,
        skipped: MutableList<SkippedRow>
    ): LegacyProject? {
        val id = row.long("id")
        fun skip(reason: String): LegacyProject? = null.also { skipped += SkippedRow("projects", id, reason) }
        val target = row.string("target") ?: return skip("its target language row is missing")
        val bookSlug = row.string("book") ?: return skip("its book row is missing")
        val version = row.string("version") ?: return skip("its version row is missing")
        val anthology = row.string("anthology") ?: return skip("its book's anthology row is missing")
        val anthologyId = row.long("anthology_id")
        val parser = TakeNameParser.of(row.string("regex"), row.string("groups"))
        val folder = translations.resolve(target).resolve(version).resolve(bookSlug)
        val files = FilesByName(folder)

        val chapters = db.query(TAKES, listOf(id)).let { rows ->
            rows.groupBy { it.long("unit_id") }.mapNotNull { (unitId, unitRows) ->
                val unit = unitRows.first()
                val chapter = unit.int("chapter")
                val start = unit.int("start_verse")
                if (unitId == null || chapter == null || start == null) {
                    skipped += SkippedRow("units", unitId, "it has no chapter or start verse")
                    return@mapNotNull null
                }
                val takes = unitRows.mapNotNull { takeRow ->
                    val takeId = takeRow.long("take_id") ?: return@mapNotNull null
                    val filename = takeRow.string("filename")
                    if (filename.isNullOrBlank()) {
                        skipped += SkippedRow("takes", takeId, "it has no filename")
                        return@mapNotNull null
                    }
                    takeFrom(takeRow, takeId, filename, bookSlug, chapter, folder, files, parser, modes, anthologyId)
                }
                chapter to LegacyChunk(unitId.toInt(), start, unit.int("chosen_take_fk"), takes.sortedBy { it.number })
            }
        }
            .groupBy({ it.first }, { it.second })
            .map { (number, chunks) -> LegacyChapter(number, chunks.sortedBy { it.startVerse }) }
            .sortedBy { it.number }

        val referenced = chapters.flatMap { c -> c.chunks.flatMap { u -> u.takes.map { it.file.canonicalFile } } }.toSet()
        return LegacyProject(
            id = id?.toInt() ?: -1,
            anthology = anthology,
            book = LegacyBook(bookSlug, row.int("book_number") ?: 0),
            targetLanguage = target,
            sourceLanguage = row.string("source"),
            versionLabel = version,
            chunking = chunkingOf(row.string("mode_type")),
            contributors = row.string("contributors"),
            folder = folder,
            chapters = chapters,
            unreferencedFiles = files.all.filter { it.canonicalFile !in referenced }
        )
    }

    private fun takeFrom(
        row: LegacyRow,
        takeId: Long,
        filename: String,
        bookSlug: String,
        chapter: Int,
        folder: File,
        files: FilesByName,
        parser: TakeNameParser?,
        modes: List<LegacyRow>,
        anthologyId: Long?
    ): LegacyTake {
        // Where the old app put it; failing that, a file of that name anywhere in the book's folder.
        val expected = folder.resolve(chapterFolder(bookSlug, chapter)).resolve(filename)
        val file = if (expected.isFile) expected else files.named(filename) ?: expected
        val onDisk = file.isFile
        val metadata = if (onDisk) LegacyWavMetadata.read(file) else null
        return LegacyTake(
            id = takeId.toInt(),
            number = row.int("take_number") ?: 0,
            rating = row.int("rating") ?: 0,
            recordedAt = row.long("timestamp")?.takeIf { it > 0 },
            file = file,
            onDisk = onDisk,
            verses = parser?.verses(filename),
            metadata = metadata?.let {
                LegacyTakeMetadata(
                    chunking = modeChunking(it.mode, modes, anthologyId),
                    verses = it.startVerse?.let { start -> start..(it.endVerse ?: start) }?.takeIf { range -> !range.isEmpty() },
                    markers = it.markers
                )
            }
        )
    }

    /** What a take's JSON `mode` (the mode's slug or name) means for its anthology. */
    private fun modeChunking(mode: String?, modes: List<LegacyRow>, anthologyId: Long?): LegacyChunking? {
        mode ?: return null
        val row = modes.firstOrNull { m ->
            m.long("anthology_fk") == anthologyId &&
                (m.string("slug")?.lowercase() == mode || m.string("name")?.lowercase() == mode)
        }
        return chunkingOf(row?.string("type"))
    }

    private fun chunkingOf(type: String?): LegacyChunking? = when (type?.lowercase()) {
        "single" -> LegacyChunking.VERSE_BY_VERSE
        "multi" -> LegacyChunking.CHUNKS
        else -> null
    }

    /** Every `<lang>/<version>/<book>` folder under [translations]. */
    private fun bookFolders(translations: File): List<File> =
        translations.listFiles().orEmpty().filter { it.isDirectory }.flatMap { language ->
            language.listFiles().orEmpty().filter { it.isDirectory }.flatMap { version ->
                version.listFiles().orEmpty().filter { it.isDirectory }
            }
        }.sortedBy { it.path }

    /** The files in a book's folder, found by name. */
    private class FilesByName(folder: File) {
        val all: List<File> = if (folder.isDirectory) folder.walkTopDown().filter { it.isFile }.toList() else emptyList()
        private val byName = all.groupBy { it.name }
        fun named(name: String): File? = byName[name]?.singleOrNull()
    }

    /**
     * The verse range in a take's filename, by its anthology's regex and the groups the old app
     * stored with it: language, version, book number, book, chapter, start verse, end verse, take.
     */
    private class TakeNameParser(private val regex: Regex, private val start: Int, private val end: Int) {
        fun verses(filename: String): IntRange? {
            val match = regex.find(filename) ?: return null
            val first = match.groupValues.getOrNull(start)?.toIntOrNull() ?: return null
            val last = match.groupValues.getOrNull(end)?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: first
            return (first..last).takeIf { !it.isEmpty() }
        }

        companion object {
            fun of(regex: String?, groups: String?): TakeNameParser? {
                val locations = groups?.trim()?.split(Regex("\\s+"))?.map { it.toIntOrNull() } ?: return null
                val start = locations.getOrNull(5) ?: return null
                val end = locations.getOrNull(6) ?: return null
                val compiled = regex?.let { runCatching { Regex(it) }.getOrNull() } ?: return null
                return TakeNameParser(compiled, start, end)
            }
        }
    }

    companion object {
        const val SCHEMA_VERSION = 4

        /**
         * The old app's chapter folder: three digits for Psalms, two for every other book. (It
         * formatted them in the device's locale; a folder named otherwise is still found by name.)
         */
        fun chapterFolder(bookSlug: String, chapter: Int): String =
            chapter.toString().padStart(if (bookSlug == "psa") 3 else 2, '0')

        private val PROJECTS = """
            SELECT p._id AS id, tl.code AS target, sl.code AS source,
                   b.slug AS book, b.number AS book_number,
                   a._id AS anthology_id, a.slug AS anthology, a.regex AS regex, a.groups AS groups,
                   v.slug AS version, m.type AS mode_type, p.contributors AS contributors
            FROM projects p
            LEFT JOIN languages tl ON tl._id = p.target_language_fk
            LEFT JOIN languages sl ON sl._id = p.source_lang_fk
            LEFT JOIN books b ON b._id = CAST(p.book_fk AS INTEGER)
            LEFT JOIN anthologies a ON a._id = b.anthology_fk
            LEFT JOIN versions v ON v._id = p.version_fk
            LEFT JOIN modes m ON m._id = CAST(p.mode_fk AS INTEGER)
            ORDER BY p._id
        """.trimIndent()

        private val TAKES = """
            SELECT u._id AS unit_id, c.number AS chapter, u.start_verse AS start_verse,
                   u.chosen_take_fk AS chosen_take_fk,
                   t._id AS take_id, t.number AS take_number, t.rating AS rating,
                   t.filename AS filename, t.timestamp AS timestamp
            FROM units u
            LEFT JOIN chapters c ON c._id = u.chapter_fk
            LEFT JOIN takes t ON t.unit_fk = u._id
            WHERE u.project_fk = ?
            ORDER BY u._id, t.number
        """.trimIndent()
    }
}
