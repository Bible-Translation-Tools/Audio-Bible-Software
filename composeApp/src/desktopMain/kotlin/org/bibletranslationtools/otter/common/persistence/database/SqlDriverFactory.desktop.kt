package org.bibletranslationtools.otter.common.persistence.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.Properties

actual class DriverFactory {
    actual fun createDriver(): SqlDriver {
        val driver: SqlDriver = JdbcSqliteDriver("jdbc:sqlite:sqldelight_test.db", Properties(), SqlDelightAppDatabase.Schema)
        return driver
    }
}