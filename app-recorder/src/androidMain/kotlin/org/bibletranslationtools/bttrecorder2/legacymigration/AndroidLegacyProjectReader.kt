package org.bibletranslationtools.bttrecorder2.legacymigration

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

/**
 * The old BTT-Recorder's projects on this device. This app is installed over the old one (same
 * application id), so its database and recordings are this app's own:
 * - the database, `translation_projects`, among the app's databases;
 * - the recordings, under `translations/` in the app's external files folder.
 *
 * The database is opened read-only; [LegacyProjectReader] does the reading.
 */
class AndroidLegacyProjectReader(context: Context) : ILegacyProjectReader by LegacyProjectReader(
    openDatabase = {
        context.getDatabasePath(DATABASE_NAME)
            .takeIf { it.isFile }
            ?.let { AndroidLegacyDatabase(SQLiteDatabase.openDatabase(it.path, null, SQLiteDatabase.OPEN_READONLY)) }
    },
    translationsDir = { context.getExternalFilesDir(null)?.resolve(TRANSLATIONS_DIR) }
) {
    private companion object {
        const val DATABASE_NAME = "translation_projects"
        const val TRANSLATIONS_DIR = "translations"
    }
}

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
