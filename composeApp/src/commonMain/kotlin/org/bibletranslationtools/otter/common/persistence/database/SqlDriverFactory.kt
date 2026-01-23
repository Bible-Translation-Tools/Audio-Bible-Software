package org.bibletranslationtools.otter.common.persistence.database

import app.cash.sqldelight.db.SqlDriver


expect class DriverFactory {
    fun createDriver(): SqlDriver
}

fun createDatabase(driverFactory: DriverFactory): SqlDelightAppDatabase {
    val driver = driverFactory.createDriver()
    val database = SqlDelightAppDatabase(driver)

    return database
}