package org.bibletranslationtools.otter.database

import org.bibletranslationtools.otter.common.api.persistence.AppDatabase
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.persistence.database.daos.CheckingStatusDao
import org.bibletranslationtools.otter.common.persistence.database.daos.CollectionDao
import org.bibletranslationtools.otter.common.persistence.database.daos.ContentDao
import org.bibletranslationtools.otter.common.persistence.database.daos.ContentTypeDao
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
import org.slf4j.LoggerFactory
import java.io.File
import java.sql.Connection

//actual fun getAppDatabase(
//    databaseFile: File,
//    directoryProvider: IDirectoryProvider
//): AppDatabase {
//    return JvmAppDatabase(databaseFile, directoryProvider)
//}

class JvmAppDatabase(
    databaseFile: File,
    directoryProvider: IDirectoryProvider
): AppDatabase {
    val logger = LoggerFactory.getLogger(AppDatabase::class.java)

    override val dsl: DSLContext = TODO()
    private val connection: Connection

    init {
        TODO()
//        System.setProperty("org.jooq.no-logo", "true")
//
//        // Load the SQLite JDBC drivers
//        Class
//            .forName("org.sqlite.JDBC")
//            .getDeclaredConstructor()
//            .newInstance()
//
//        val sqLiteDataSource = createSQLiteDataSource(databaseFile)
//        connection = sqLiteDataSource.connection
//
//        // Create the jooq dsl
//        dsl = DSL.using(connection, SQLDialect.SQLITE)
//
//        val dbDoesNotExist = !databaseFile.exists() || databaseFile.length() == 0L
//        if (dbDoesNotExist) {
//            setup()
//        }
//        DatabaseMigrator(directoryProvider).migrate(dsl)
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

    override val installedEntityDao =
        org.bibletranslationtools.otter.common.persistence.database.daos.InstalledEntityDao(dsl)
    override val translationDao = TranslationDao(dsl)
    override val versificationDao = VersificationDao(dsl)
    override val workbookTypeDao = WorkbookTypeDao(dsl)
    override val workbookDescriptorDao = WorkbookDescriptorDao(dsl)
    override val checkingStatusDao = CheckingStatusDao(dsl)

    fun close() {
        connection.close()
    }

    companion object {
        init {
            System.setProperty("org.jooq.no-logo", "true")
        }

//        fun getDatabaseVersion(databaseFile: File): Int? {
//            if (!databaseFile.exists() || databaseFile.length() == 0L) {
//                return null
//            }
//            val sqliteDataSource: SQLiteDataSource = createSQLiteDataSource(databaseFile)
//            val dsl = DSL.using(sqliteDataSource, SQLDialect.SQLITE)
//            return try {
//                dsl
//                    .select()
//                    .from(InstalledEntity.INSTALLED_ENTITY)
//                    .where(InstalledEntity.INSTALLED_ENTITY.NAME.eq(DATABASE_INSTALLABLE_NAME))
//                    .fetchSingle {
//                        it.get(InstalledEntity.INSTALLED_ENTITY.VERSION)
//                    }
//            } catch (e: DataAccessException) {
//                null
//            }
//        }
//
//        private fun createSQLiteDataSource(databaseFile: File): SQLiteDataSource {
//            val sqLiteDataSource = SQLiteDataSource()
//            sqLiteDataSource.url = "jdbc:sqlite:${databaseFile.toURI().path}"
//            sqLiteDataSource.config.toProperties().setProperty("foreign_keys", "true")
//            return sqLiteDataSource
//        }
    }
}