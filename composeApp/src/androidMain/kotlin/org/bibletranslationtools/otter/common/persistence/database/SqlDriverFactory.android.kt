package org.bibletranslationtools.otter.common.persistence.database

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

actual class DriverFactory(private val context: Context) {
    actual fun createDriver(): SqlDriver {
        return AndroidSqliteDriver(SqlDelightAppDatabase.Schema, context, "sqldelight_test.db")
    }
}