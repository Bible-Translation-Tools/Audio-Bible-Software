package org.bibletranslationtools.bttrecorder2.legacymigration

import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * The old BTT-Recorder's storage, for tests: its database with the schema it created (version 4,
 * the CREATE statements exactly as its ProjectContract builds them, missing commas included) and
 * its `translations/` folder of takes.
 */
internal class OldAppStorage(root: File) : AutoCloseable {

    val databaseFile: File = root.resolve("databases/translation_projects")
    val translations: File = root.resolve("files/translations")

    private val opened = lazy {
        databaseFile.parentFile.mkdirs()
        DriverManager.getConnection("jdbc:sqlite:${databaseFile.path}").also { c ->
            c.createStatement().use { s ->
                SCHEMA.forEach(s::execute)
                s.execute("PRAGMA user_version = 4")
            }
        }
    }
    private val connection: Connection by opened

    /** This storage as the archive and the delete see it. */
    fun storage(): ILegacyStorage = object : ILegacyStorage {
        override val databaseFile: File? get() = this@OldAppStorage.databaseFile.takeIf { it.isFile }
        override val translationsDir: File get() = translations
        override fun deleteDatabase(): Boolean = this@OldAppStorage.databaseFile.let { it.delete() || !it.exists() }
    }

    /** The reader as the app builds it, over this storage. */
    fun reader(): LegacyProjectReader = LegacyProjectReader(
        openDatabase = { databaseFile.takeIf { it.isFile }?.let(::JdbcLegacyDatabase) },
        translationsDir = { translations }
    )

    fun execute(sql: String, vararg args: Any?): Long =
        connection.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS).use { s ->
            args.forEachIndexed { i, arg -> s.setObject(i + 1, arg) }
            s.executeUpdate()
            s.generatedKeys.use { if (it.next()) it.getLong(1) else -1 }
        }

    fun schemaVersion(version: Int) {
        connection.createStatement().use { it.execute("PRAGMA user_version = $version") }
    }

    // ── the old app's rows ─────────────────────────────────────────────────────────────────

    fun language(code: String) = execute("INSERT INTO languages (code, name) VALUES (?, ?)", code, code)
    fun version(slug: String) = execute("INSERT INTO versions (slug, name) VALUES (?, ?)", slug, slug)

    /** A Bible anthology as the old app's plugin installed it: its filename regex and groups. */
    fun anthology(slug: String): Long {
        val id = execute(
            "INSERT INTO anthologies (slug, name, resource, sort, regex, groups, mask) VALUES (?, ?, 'bible', 1, ?, ?, '1001111111')",
            slug, slug, BIBLE_REGEX, BIBLE_GROUPS
        )
        execute("INSERT INTO modes (slug, name, type, anthology_fk) VALUES ('verse', 'verse', 'single', ?)", id)
        execute("INSERT INTO modes (slug, name, type, anthology_fk) VALUES ('chunk', 'chunk', 'multi', ?)", id)
        return id
    }

    fun mode(anthology: Long, slug: String): Long =
        connection.prepareStatement("SELECT _id FROM modes WHERE anthology_fk = ? AND slug = ?").use { s ->
            s.setLong(1, anthology); s.setString(2, slug)
            s.executeQuery().use { it.next(); it.getLong(1) }
        }

    fun book(slug: String, number: Int, anthology: Long) =
        execute("INSERT INTO books (slug, name, number, anthology_fk) VALUES (?, ?, ?, ?)", slug, slug, number, anthology)

    /** `book_fk` and `mode_fk` are TEXT columns in the old schema; the old app stored ids in them. */
    fun project(target: Long, book: Long, version: Long, mode: Long?, source: Long? = null, contributors: String? = null) =
        execute(
            "INSERT INTO projects (target_language_fk, book_fk, version_fk, mode_fk, source_lang_fk, contributors, progress) VALUES (?, ?, ?, ?, ?, ?, 0)",
            target, book.toString(), version, mode?.toString(), source, contributors
        )

    fun chapter(project: Long, number: Int) =
        execute("INSERT INTO chapters (project_fk, number, progress, checking) VALUES (?, ?, 0, 0)", project, number)

    /** A unit, keyed by its first verse; the old app never wrote `end_verse`. */
    fun unit(project: Long, chapter: Long, startVerse: Int) =
        execute("INSERT INTO units (project_fk, chapter_fk, start_verse) VALUES (?, ?, ?)", project, chapter, startVerse)

    fun take(unit: Long, number: Int, filename: String?, rating: Int = 0, timestamp: Long = 0) =
        execute(
            "INSERT INTO takes (unit_fk, rating, number, filename, timestamp, user_fk) VALUES (?, ?, ?, ?, ?, 0)",
            unit, rating, number, filename, timestamp
        )

    fun choose(unit: Long, take: Long) = execute("UPDATE units SET chosen_take_fk = ? WHERE _id = ?", take, unit)

    /** Where the old app filed a take: `<lang>/<version>/<book>/<chapter>/<name>`. */
    fun takeFile(language: String, version: String, book: String, chapter: Int, name: String): File =
        translations.resolve("$language/$version/$book/${LegacyProjectReader.chapterFolder(book, chapter)}/$name")

    override fun close() {
        if (opened.isInitialized()) connection.close()
    }

    companion object {
        const val BIBLE_REGEX =
            "([a-zA-Z]{2,3}[-[\\d\\w]+]*)_([a-zA-Z]{3})_b([\\d]{2})_([1-3]*[a-zA-Z]+)_c([\\d]{2,3})_v([\\d]{2,3})(-([\\d]{2,3}))?(_t([\\d]{2}))?(.wav)?"
        const val BIBLE_GROUPS = "1 2 3 4 5 6 8 10"

        /** A take's name as the old app made it, e.g. `aa_ulb_b59_jas_c01_v04-06_t02.wav`. */
        fun takeName(language: String, version: String, bookNumber: Int, book: String, chapter: Int, verses: IntRange, take: Int) =
            "${language}_${version}_b${bookNumber.pad(2)}_${book}_c${chapter.pad(if (book == "psa") 3 else 2)}" +
                "_v${verses.first.pad(2)}${if (verses.last != verses.first) "-${verses.last.pad(2)}" else ""}_t${take.pad(2)}.wav"

        private fun Int.pad(width: Int) = toString().padStart(width, '0')

        private val SCHEMA = listOf(
            "CREATE TABLE languages (_id INTEGER PRIMARY KEY,code TEXT,name TEXT,CONSTRAINT cols_unique UNIQUE(code));",
            "CREATE TABLE books (_id INTEGER PRIMARY KEY,slug TEXT,name TEXT,number INTEGER,anthology_fk INTEGER," +
                "FOREIGN KEY(anthology_fk) REFERENCES anthologies(_id)CONSTRAINT cols_unique UNIQUE(slug,anthology_fk));",
            "CREATE TABLE projects (_id INTEGER PRIMARY KEY,target_language_fk INTEGER,book_fk TEXT,version_fk INTEGER," +
                "mode_fk TEXT,source_lang_fk INTEGER,source_audio_path TEXT,contributors TEXT,notes TEXT,progress INTEGER," +
                "FOREIGN KEY(target_language_fk) REFERENCES languages(_id)FOREIGN KEY(source_lang_fk) REFERENCES languages(_id)" +
                "FOREIGN KEY(version_fk) REFERENCES versions(_id)CONSTRAINT cols_unique UNIQUE(book_fk,target_language_fk,version_fk) );",
            "CREATE TABLE chapters (_id INTEGER PRIMARY KEY,project_fk INTEGER,number INTEGER,notes TEXT,progress INTEGER," +
                "checking INTEGER,FOREIGN KEY(project_fk) REFERENCES projects(_id)CONSTRAINT cols_unique UNIQUE(project_fk,number));",
            "CREATE TABLE units (_id INTEGER PRIMARY KEY, project_fk INTEGER,chapter_fk INTEGER,start_verse INTEGER," +
                "end_verse INTEGER,notes TEXT,chosen_take_fk INTEGER,FOREIGN KEY(project_fk) REFERENCES projects(_id)" +
                "FOREIGN KEY(chapter_fk) REFERENCES chapters(_id)FOREIGN KEY(chosen_take_fk) REFERENCES takes(_id)" +
                "CONSTRAINT cols_unique UNIQUE(project_fk,chapter_fk,start_verse));",
            "CREATE TABLE takes (_id INTEGER PRIMARY KEY, unit_fk INTEGER,rating INTEGER,notes TEXT,number INTEGER," +
                "filename TEXT,timestamp INTEGER,user_fk INTEGER,CONSTRAINT cols_unique UNIQUE(unit_fk,number));",
            "CREATE TABLE anthologies (_id INTEGER PRIMARY KEY,slug TEXT,name TEXT,resource TEXT,sort INTEGER,regex TEXT," +
                "groups TEXT,mask TEXT,class TEXT,jar TEXT,CONSTRAINT cols_unique UNIQUE(slug));",
            "CREATE TABLE modes (_id INTEGER PRIMARY KEY,slug TEXT,name TEXT,type TEXT,anthology_fk INTEGER," +
                "CONSTRAINT cols_unique UNIQUE(slug,anthology_fk));",
            "CREATE TABLE versions (_id INTEGER PRIMARY KEY,slug TEXT,name TEXT,CONSTRAINT cols_unique UNIQUE(slug));",
            "CREATE TABLE version_relationship (_id INTEGER PRIMARY KEY,version_fk INTEGER,anthology_fk INTEGER," +
                "FOREIGN KEY(version_fk) REFERENCES versions(_id)FOREIGN KEY(anthology_fk) REFERENCES anthologies(_id)" +
                "CONSTRAINT cols_unique UNIQUE(version_fk,anthology_fk));",
            "CREATE TABLE tr_users (_id INTEGER PRIMARY KEY,audio TEXT,hash TEXT,CONSTRAINT cols_unique UNIQUE(audio,hash) );"
        )
    }
}

/**
 * The old database over JDBC, bound the way Android binds rawQuery arguments: as strings.
 */
internal class JdbcLegacyDatabase(file: File) : LegacyDatabase {
    private val connection: Connection = DriverManager.getConnection(
        "jdbc:sqlite:${file.path}", java.util.Properties().apply { setProperty("open_mode", "1") } // SQLITE_OPEN_READONLY
    )

    override fun query(sql: String, args: List<Any?>): List<LegacyRow> =
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { i, arg -> statement.setString(i + 1, arg.toString()) }
            statement.executeQuery().use { rs ->
                val columns = (1..rs.metaData.columnCount).map { rs.metaData.getColumnLabel(it) }
                buildList {
                    while (rs.next()) add(LegacyRow(columns.associateWith { rs.getObject(it) }))
                }
            }
        }

    override fun close() = connection.close()
}
