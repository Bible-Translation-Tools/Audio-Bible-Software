package org.bibletranslationtools.bttrecorder2.legacymigration

/**
 * The old app's database, opened read-only. The queries are [LegacyProjectReader]'s; a platform
 * only runs them, so the reading can be tested off Android against a database with the old schema.
 */
interface LegacyDatabase : AutoCloseable {
    /** The rows [sql] returns, each column by name; [args] bind its `?` placeholders in order. */
    fun query(sql: String, args: List<Any?> = emptyList()): List<LegacyRow>
}

/**
 * One row, by column name. The old schema declares some ids as TEXT (`projects.book_fk`,
 * `projects.mode_fk`), so numbers are read from whichever form a column holds.
 */
class LegacyRow(private val values: Map<String, Any?>) {
    fun string(column: String): String? = values[column]?.toString()

    fun long(column: String): Long? = when (val value = values[column]) {
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }

    fun int(column: String): Int? = long(column)?.toInt()
}
