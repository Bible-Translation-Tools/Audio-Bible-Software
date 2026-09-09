package org.bibletranslationtools.bttrecorder2.migration

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Reads the legacy Android BTT-Recorder's data in place.
 *
 * Reaching that data at all depends on `:app-recorder` shipping the legacy `applicationId`, since
 * Android scopes an app's private database and external files directories by it.
 *
 * The database is opened read-only and never written; see [LegacyRecorderStore] for why it is kept
 * rather than pruned. Nothing here throws: a missing, corrupt or half-created legacy database has
 * to degrade to "no legacy data" rather than a crash on first launch.
 */
class AndroidLegacyRecorderStore(private val context: Context) : LegacyRecorderStore {

    private val logger = LoggerFactory.getLogger(AndroidLegacyRecorderStore::class.java)

    private val databaseFile: File get() = context.getDatabasePath(DATABASE_NAME)

    /** `<externalFilesDir>/translations`, matching the legacy `DirectoryProvider.translationsDir`. */
    private val translationsDir: File? get() = context.getExternalFilesDir(null)?.resolve(TRANSLATIONS_DIR)

    /** `<externalFilesDir>/source_audio`, where the legacy app staged the archives it was given. */
    private val sourceAudioDir: File? get() = context.getExternalFilesDir(null)?.resolve(SOURCE_AUDIO_DIR)

    override fun hasLegacyData(): Boolean = databaseFile.isFile && databaseFile.length() > 0

    override fun readProjects(): List<LegacyProject> {
        if (!hasLegacyData()) return emptyList()
        return try {
            SQLiteDatabase.openDatabase(
                databaseFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            ).use { db -> db.readProjects() }
        } catch (e: Exception) {
            logger.error("Could not read the legacy recorder database at ${databaseFile.path}", e)
            emptyList()
        }
    }

    private fun SQLiteDatabase.readProjects(): List<LegacyProject> {
        // One query for the project header, joining out the slug tables. In this schema the
        // language slug lives in `languages.code`, not `languages.slug`.
        val sql = """
            SELECT p._id, tl.code AS target_code, sl.code AS source_code,
                   v.slug AS version_slug, b.slug AS book_slug, b.number AS book_number,
                   m.type AS mode_type, m.slug AS mode_slug,
                   p.contributors, p.source_audio_path
            FROM projects p
            JOIN languages tl ON tl._id = p.target_language_fk
            LEFT JOIN languages sl ON sl._id = p.source_lang_fk
            JOIN versions v ON v._id = p.version_fk
            JOIN books b ON b._id = p.book_fk
            LEFT JOIN modes m ON m._id = p.mode_fk
            ORDER BY p._id
        """.trimIndent()

        val projects = mutableListOf<LegacyProject>()
        rawQuery(sql, null).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.int("_id") ?: continue
                val targetCode = cursor.string("target_code") ?: continue
                val versionSlug = cursor.string("version_slug") ?: continue
                val bookSlug = cursor.string("book_slug") ?: continue
                projects += LegacyProject(
                    id = id,
                    targetLanguageSlug = targetCode,
                    sourceLanguageSlug = cursor.string("source_code"),
                    versionSlug = versionSlug,
                    bookSlug = bookSlug,
                    bookNumber = cursor.int("book_number") ?: 0,
                    mode = LegacyMode.fromTypeOrSlug(
                        cursor.string("mode_type") ?: cursor.string("mode_slug")
                    ),
                    contributors = cursor.string("contributors"),
                    sourceAudioPath = cursor.string("source_audio_path"),
                    chapters = emptyList()
                )
            }
        }

        return projects.map { it.copy(chapters = readChapters(it.id)) }
    }

    private fun SQLiteDatabase.readChapters(projectId: Int): List<LegacyChapter> {
        val chapters = mutableListOf<Pair<Int, Int>>() // chapter id to number
        rawQuery(
            "SELECT _id, number FROM chapters WHERE project_fk = ? ORDER BY number",
            arrayOf(projectId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.int("_id") ?: continue
                val number = cursor.int("number") ?: continue
                chapters += id to number
            }
        }
        return chapters.map { (chapterId, number) ->
            LegacyChapter(number = number, units = readUnits(chapterId))
        }
    }

    private fun SQLiteDatabase.readUnits(chapterId: Int): List<LegacyUnit> {
        val units = mutableListOf<Triple<Int, IntRange, Int?>>() // id, verse range, chosen take
        rawQuery(
            """
            SELECT _id, start_verse, end_verse, chosen_take_fk
            FROM units WHERE chapter_fk = ? ORDER BY start_verse
            """.trimIndent(),
            arrayOf(chapterId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.int("_id") ?: continue
                val start = cursor.int("start_verse") ?: continue
                // A verse-mode unit can leave end_verse unset, meaning a single verse.
                val end = cursor.int("end_verse")?.takeIf { it >= start } ?: start
                units += Triple(id, start..end, cursor.int("chosen_take_fk"))
            }
        }
        return units.map { (unitId, range, chosenTake) ->
            LegacyUnit(
                startVerse = range.first,
                endVerse = range.last,
                chosenTakeId = chosenTake,
                takes = readTakes(unitId)
            )
        }
    }

    private fun SQLiteDatabase.readTakes(unitId: Int): List<LegacyTake> {
        val takes = mutableListOf<LegacyTake>()
        rawQuery(
            "SELECT _id, number, filename FROM takes WHERE unit_fk = ? ORDER BY number",
            arrayOf(unitId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.int("_id") ?: continue
                val filename = cursor.string("filename")?.takeIf { it.isNotBlank() } ?: continue
                takes += LegacyTake(
                    id = id,
                    number = cursor.int("number") ?: 0,
                    // Stored as a bare filename, but a full path is tolerated.
                    filename = File(filename).name
                )
            }
        }
        return takes
    }

    override fun takeFile(project: LegacyProject, chapterNumber: Int, take: LegacyTake): File? =
        chapterDir(project, chapterNumber)?.resolve(take.filename)?.takeIf { it.isFile }

    override fun sourceAudioFile(project: LegacyProject): File? {
        val recorded = project.sourceAudioPath?.takeIf { it.isNotBlank() }?.let(::File)
        // The recorded path is wherever the user picked the file from, which scoped storage may
        // no longer allow. The legacy app also copied it into its own source_audio directory, so
        // fall back to matching by basename there.
        if (recorded != null && recorded.isFile) return recorded
        val name = recorded?.name ?: return null
        return sourceAudioDir?.resolve(name)?.takeIf { it.isFile }
    }

    override fun audioSizeBytes(project: LegacyProject): Long =
        projectDir(project)?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    override fun deleteProjectAudio(project: LegacyProject): Boolean {
        val dir = projectDir(project) ?: return true
        if (!dir.exists()) return true
        val deleted = try {
            dir.deleteRecursively()
        } catch (e: Exception) {
            logger.error("Could not delete legacy audio at ${dir.path}", e)
            false
        }
        // Prune the version and language directories above it if now empty, as the legacy app's
        // own project deletion did.
        if (deleted) {
            dir.parentFile?.deleteIfEmpty()?.parentFile?.deleteIfEmpty()
        }
        return deleted
    }

    private fun File.deleteIfEmpty(): File? {
        if (isDirectory && listFiles()?.isEmpty() == true) delete()
        return this
    }

    /** `translations/<lang>/<version>/<book>`, the legacy project directory layout. */
    private fun projectDir(project: LegacyProject): File? = translationsDir
        ?.resolve(project.targetLanguageSlug)
        ?.resolve(project.versionSlug)
        ?.resolve(project.bookSlug)

    private fun chapterDir(project: LegacyProject, chapterNumber: Int): File? =
        projectDir(project)?.resolve(chapterFolder(project.bookSlug, chapterNumber))

    /** Legacy chapter directory name: three digits for Psalms, two for every other book. */
    private fun chapterFolder(bookSlug: String, chapter: Int): String =
        if (bookSlug == PSALMS_SLUG) "%03d".format(chapter) else "%02d".format(chapter)

    private fun Cursor.string(column: String): String? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getString(index)
    }

    private fun Cursor.int(column: String): Int? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getInt(index)
    }

    companion object {
        /** `ProjectDatabaseHelper.DATABASE_NAME`. */
        private const val DATABASE_NAME = "translation_projects"
        private const val TRANSLATIONS_DIR = "translations"
        private const val SOURCE_AUDIO_DIR = "source_audio"
        private const val PSALMS_SLUG = "psa"
    }
}
