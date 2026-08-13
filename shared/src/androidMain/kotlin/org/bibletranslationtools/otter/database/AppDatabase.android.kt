
package org.bibletranslationtools.otter.database

import android.content.Context
import com.readystatesoftware.sqliteasset.SQLiteAssetHelper
import org.bibletranslationtools.otter.common.persistence.database.IAppDatabase
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.persistence.database.DatabaseMigrator
import org.bibletranslationtools.otter.common.persistence.database.daos.CheckingStatusDao
import org.bibletranslationtools.otter.common.persistence.database.daos.CollectionDao
import org.bibletranslationtools.otter.common.persistence.database.daos.ContentDao
import org.bibletranslationtools.otter.common.persistence.database.daos.ContentTypeDao
import org.bibletranslationtools.otter.common.persistence.database.daos.InstalledEntityDao
import org.bibletranslationtools.otter.common.persistence.database.daos.LanguageDao
import org.bibletranslationtools.otter.common.persistence.database.daos.MarkerDao
import org.bibletranslationtools.otter.common.persistence.database.daos.ResourceLinkDao
import org.bibletranslationtools.otter.common.persistence.database.daos.ResourceMetadataDao
import org.bibletranslationtools.otter.common.persistence.database.daos.SubtreeHasResourceDao
import org.bibletranslationtools.otter.common.persistence.database.daos.TakeDao
import org.bibletranslationtools.otter.common.persistence.database.daos.TranslationDao
import org.bibletranslationtools.otter.common.persistence.database.daos.VersificationDao
import org.bibletranslationtools.otter.common.persistence.database.daos.WorkbookDescriptorDao
import org.bibletranslationtools.otter.common.persistence.database.daos.WorkbookTypeDao
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.conf.Settings
import org.jooq.conf.StatementType
import org.jooq.impl.DSL
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * The oldest SQLite that understands `INSERT … ON CONFLICT … DO NOTHING/DO UPDATE`, which jOOQ
 * emits unconditionally for SQLDialect.SQLITE (LanguageDao.insertAll, InstalledEntityDao.upsert).
 * Below this, those statements fail with `near "on": syntax error`.
 */
private const val MIN_UPSERT_SQLITE = "3.24.0"

/**
 * Picks the JDBC driver by what the PLATFORM's SQLite can actually do.
 *
 * Android 7 ships SQLite 3.9.2 — verified on an API 24 emulator — and upsert only arrived in
 * 3.24. Rather than assume an API level maps to a SQLite version (OEMs vary, and the mapping has
 * shifted across releases), this asks the device directly and falls back to the sqlite-jdbc build
 * bundled in jniLibs only when the platform is too old.
 *
 * Newer devices therefore keep the original SQLDroid path over the system SQLite: no native load,
 * no second copy of the engine in memory, and the behaviour that has been shipping.
 */
private fun openConnection(databasePath: String): Connection {
    val platformVersion = platformSqliteVersion()
    val platformCanUpsert = platformVersion != null &&
        compareVersions(platformVersion, MIN_UPSERT_SQLITE) >= 0

    return if (platformCanUpsert) {
        Class.forName("org.sqldroid.SQLDroidDriver").newInstance()
        DriverManager.getConnection("jdbc:sqlite:$databasePath")
    } else {
        // Xerial resolves libsqlitejdbc.so through System.loadLibrary, which finds the copy the
        // installer unpacked from jniLibs into nativeLibraryDir. Its own extract-to-tmp fallback
        // cannot work here: Android blocks dlopen from app-writable storage for targetSdk 29+.
        Class.forName("org.sqlite.JDBC").newInstance()
        DriverManager.getConnection("jdbc:sqlite:$databasePath")
    }
}

/** The SQLite the platform itself provides, or null if it cannot be determined. */
private fun platformSqliteVersion(): String? = try {
    android.database.sqlite.SQLiteDatabase
        .create(null)
        .use { db ->
            db.rawQuery("select sqlite_version()", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }
} catch (e: Exception) {
    null
}

/** Numeric dotted-version compare; missing components count as 0. */
private fun compareVersions(a: String, b: String): Int {
    val left = a.split(".").map { it.toIntOrNull() ?: 0 }
    val right = b.split(".").map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(left.size, right.size)) {
        val diff = (left.getOrNull(i) ?: 0) - (right.getOrNull(i) ?: 0)
        if (diff != 0) return diff
    }
    return 0
}

class AndroidAppDatabase(
    context: Context,
    databaseFile: File,
    directoryProvider: IDirectoryProvider
): IAppDatabase {

    override val dsl: DSLContext
    private val connection: Connection

    init {
        // SQLiteAssetHelper still owns seeding tr.sqlite out of assets and its schema version,
        // regardless of which driver ends up reading the file afterwards.
        val databasePath = SQLiteAssetHelper(context, "tr.sqlite", null, 14).writableDatabase.path

        connection = openConnection(databasePath)

        val settings = Settings()
            .withFetchWarnings(false) // This is the key fix
            .withStatementType(StatementType.STATIC_STATEMENT) // Forces inlined SQL

        dsl = DSL.using(connection, SQLDialect.SQLITE, settings)

        try {
            context.assets.open("databases/CreateAppDb.sql").use {
                setup(it)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        DatabaseMigrator(directoryProvider).migrate(dsl)
    }

    // Create the DAOs
    override val languageDao = LanguageDao(dsl)
    override val resourceMetadataDao = ResourceMetadataDao(dsl)
    override val collectionDao = CollectionDao(dsl)
    override val contentTypeDao = ContentTypeDao(dsl)
    override val contentDao = ContentDao(dsl, contentTypeDao)
    override val resourceLinkDao = ResourceLinkDao(dsl)
    override val subtreeHasResourceDao = SubtreeHasResourceDao(dsl)
    override val takeDao = TakeDao(dsl)
    override val markerDao = MarkerDao(dsl)

    override val installedEntityDao = InstalledEntityDao(dsl)
    override val translationDao = TranslationDao(dsl)
    override val versificationDao = VersificationDao(dsl)
    override val workbookTypeDao = WorkbookTypeDao(dsl)
    override val workbookDescriptorDao = WorkbookDescriptorDao(dsl)
    override val checkingStatusDao = CheckingStatusDao(dsl)
}