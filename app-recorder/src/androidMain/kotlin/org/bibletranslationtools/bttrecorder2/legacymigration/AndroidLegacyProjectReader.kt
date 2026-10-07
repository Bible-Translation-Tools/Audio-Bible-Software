package org.bibletranslationtools.bttrecorder2.legacymigration

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * The old BTT-Recorder's data on this device. This app is installed over the old one (same
 * application id), so its database and recordings are this app's own:
 * - the database, `translation_projects`, among the app's databases;
 * - the recordings, under `translations/` in the app's external files folder.
 */
class AndroidLegacyStorage(private val context: Context) : ILegacyStorage {
    override val databaseFile: File?
        get() = context.getDatabasePath(DATABASE_NAME).takeIf { it.isFile }

    override val translationsDir: File?
        get() = context.getExternalFilesDir(null)?.resolve(TRANSLATIONS_DIR)

    /** Its journal files too, as SQLite leaves them beside it. */
    override fun deleteDatabase(): Boolean =
        context.deleteDatabase(DATABASE_NAME) || !context.getDatabasePath(DATABASE_NAME).exists()

    private companion object {
        const val DATABASE_NAME = "translation_projects"
        const val TRANSLATIONS_DIR = "translations"
    }
}

/** The old app's projects, read from [storage]; the database is opened read-only. */
class AndroidLegacyProjectReader(storage: ILegacyStorage) : ILegacyProjectReader by LegacyProjectReader(
    openDatabase = {
        storage.databaseFile?.let {
            AndroidLegacyDatabase(SQLiteDatabase.openDatabase(it.path, null, SQLiteDatabase.OPEN_READONLY))
        }
    },
    translationsDir = { storage.translationsDir }
)

private class AndroidLegacyDatabase(private val db: SQLiteDatabase) : LegacyDatabase {

    override fun query(sql: String, args: List<Any?>): List<LegacyRow> =
        // rawQuery binds its arguments as strings; SQLite compares them to the INTEGER ids by affinity.
        db.rawQuery(sql, args.map { it.toString() }.toTypedArray()).use { cursor ->
            val rows = mutableListOf<LegacyRow>()
            while (cursor.moveToNext()) {
                rows += LegacyRow(cursor.columnNames.indices.associate { i -> cursor.getColumnName(i) to cursor.valueAt(i) })
            }
            rows
        }

    private fun Cursor.valueAt(index: Int): Any? = when (getType(index)) {
        Cursor.FIELD_TYPE_INTEGER -> getLong(index)
        Cursor.FIELD_TYPE_FLOAT -> getDouble(index)
        Cursor.FIELD_TYPE_STRING -> getString(index)
        else -> null
    }

    override fun close() = db.close()
}
