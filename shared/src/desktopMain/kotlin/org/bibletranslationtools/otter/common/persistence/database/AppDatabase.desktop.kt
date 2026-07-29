package org.bibletranslationtools.otter.common.persistence.database

import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
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
import org.bibletranslationtools.otter_db.jooq.tables.InstalledEntity
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import java.io.File
import java.sql.Connection
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException

import org.sqlite.SQLiteDataSource
import java.io.IOException

const val CREATION_SCRIPT = "sql/CreateAppDb.sql"

class AppDatabase(
    databaseFile: File,
    directoryProvider: IDirectoryProvider
): IAppDatabase {
    val logger = LoggerFactory.getLogger(IAppDatabase::class.java)

    override val dsl: DSLContext
    private val connection: Connection

    init {
        System.setProperty("org.jooq.no-logo", "true")

        // Load the SQLite JDBC drivers
        Class
            .forName("org.sqlite.JDBC")
            .getDeclaredConstructor()
            .newInstance()

        val sqLiteDataSource = createSQLiteDataSource(databaseFile)
        connection = sqLiteDataSource.connection

        // Create the jooq dsl
        dsl = DSL.using(connection, SQLDialect.SQLITE)

        val dbDoesNotExist = !databaseFile.exists() || databaseFile.length() == 0L
        if (dbDoesNotExist) {
            setup()
        }
        DatabaseMigrator(directoryProvider).migrate(dsl)

        val isMacOS = System.getProperty("orature.isPkgMac")
        if (isMacOS != null) {
            migratePathsForSandboxedMac()
        }
    }

    private fun setup() {
        // Setup the tables
        val schemaFileStream = ClassLoader.getSystemResourceAsStream(CREATION_SCRIPT)
            ?: throw IOException("Couldn't read database creation script $CREATION_SCRIPT")

        // Make sure the database file has the tables we need
        val sqlStatements = schemaFileStream
            .bufferedReader()
            .readText()
            .split(";")
            .filter { it.isNotBlank() }
            .map { "$it;" }

        // Execute each SQL statement
        sqlStatements.forEach {
            dsl.fetch(it)
        }

        dsl.insertInto(
            InstalledEntity.INSTALLED_ENTITY,
            InstalledEntity.INSTALLED_ENTITY.NAME,
            InstalledEntity.INSTALLED_ENTITY.VERSION
        ).values(
            DATABASE_INSTALLABLE_NAME,
            SCHEMA_VERSION
        ).execute()
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
//    override val audioPluginDao = AudioPluginDao(dsl)
//    override val preferenceDao = PreferenceDao(dsl)
    override val installedEntityDao = InstalledEntityDao(dsl)
    override val translationDao = TranslationDao(dsl)
    override val versificationDao = VersificationDao(dsl)
    override val workbookTypeDao = WorkbookTypeDao(dsl)
    override val workbookDescriptorDao = WorkbookDescriptorDao(dsl)
    override val checkingStatusDao = CheckingStatusDao(dsl)

    // Transaction support
    override fun transaction(block: (DSLContext) -> Unit) {
        dsl.transaction { config ->
            // Create local transaction DSL and pass to block
            block(DSL.using(config))
        }
    }

    override fun <T> transactionResult(block: (DSLContext) -> T): T {
        return dsl.transactionResult { config ->
            // Create local transaction DSL and pass to block
            block(DSL.using(config))
        }
    }

    fun close() {
        connection.close()
    }

    /**
     * Updates the absolute paths in the database tables to the sand-boxed paths (if needed).
     * This fixes an error when installing the new version from the App Store
     * over the existing dmg-installed build.
     */
    private fun migratePathsForSandboxedMac() {
        // dublin_core_entity
        dsl.execute(
            """
                UPDATE dublin_core_entity 
                SET path = REPLACE(path, 
                   '/Library/Application Support/Orature/', 
                   '/Library/Containers/org.wycliffeassociates.otter/Data/Library/Application Support/Orature/'
                   )
                WHERE path LIKE '%/Library/Application Support/Orature/%' 
                AND path NOT LIKE '%/Library/Containers/%';
            """.trimIndent()
        )
        // take_entity
        dsl.execute(
            """
                UPDATE take_entity 
                SET path = REPLACE(path, 
                   '/Library/Application Support/Orature/', 
                   '/Library/Containers/org.wycliffeassociates.otter/Data/Library/Application Support/Orature/'
                   )
                WHERE path LIKE '%/Library/Application Support/Orature/%' 
                AND path NOT LIKE '%/Library/Containers/%';
            """.trimIndent()
        )
        // versification_entity
        dsl.execute(
            """
                UPDATE versification_entity 
                SET path = REPLACE(path, 
                   '/Library/Application Support/Orature/', 
                   '/Library/Containers/org.wycliffeassociates.otter/Data/Library/Application Support/Orature/'
                   )
                WHERE path LIKE '%/Library/Application Support/Orature/%' 
                AND path NOT LIKE '%/Library/Containers/%';
            """.trimIndent()
        )
    }

    companion object {
        init {
            System.setProperty("org.jooq.no-logo", "true")
            // passed as jvm arg through i4j launcher specifically for app store builds. DMG is fine to let jdbc
            // sqlite load however it typically does.
            val isPkgMac = System.getProperty("orature.isPkgMac")
            if (isPkgMac != null) {
                setSqlitePathsForMac()
            } else {
                println("Skipping SQLite path configuration since not a PKG Mac app")
            }
        }

        private fun setSqlitePathsForMac() {
            val osName = System.getProperty("os.name").lowercase()
            // This should only run on a mac regardless, but defensive programming here
            if (!osName.contains("mac")) return
            //from i4j. is .app/Contents/Resources/app for single bundle archives.
            val contentDir = System.getProperty("mac.appDir")
            if (contentDir == null) return
            val osArch = System.getProperty("os.arch")
            val archFolder = when {
                osArch.contains("aarch64") || osArch.contains("arm64") -> "aarch64"
                else -> "x86_64"
            }
            // Orature.app/Contents/Resources/app/mac/$arch
            val sqliteLibPath = "$contentDir/mac/$archFolder"
            println("Setting SQLite library path to: $sqliteLibPath")
            System.setProperty("org.sqlite.lib.path", sqliteLibPath)
            //should already be set in i4j for pkg files, but for extra redundancy
            System.setProperty("org.sqlite.lib.name", "libsqlitejdbc.dylib")
        }

        fun getDatabaseVersion(databaseFile: File): Int? {
            if (!databaseFile.exists() || databaseFile.length() == 0L) {
                return null
            }
            val sqliteDataSource = createSQLiteDataSource(databaseFile)
            val dsl = DSL.using(sqliteDataSource, SQLDialect.SQLITE)
            return try {
                dsl
                    .select()
                    .from(InstalledEntity.INSTALLED_ENTITY)
                    .where(InstalledEntity.INSTALLED_ENTITY.NAME.eq(DATABASE_INSTALLABLE_NAME))
                    .fetchSingle {
                        it.get(InstalledEntity.INSTALLED_ENTITY.VERSION)
                    }
            } catch (e: DataAccessException) {
                null
            }
        }

        private fun createSQLiteDataSource(databaseFile: File): SQLiteDataSource {
            val sqLiteDataSource = SQLiteDataSource()
            sqLiteDataSource.url = "jdbc:sqlite:${databaseFile.toURI().path}"
            sqLiteDataSource.config.toProperties().setProperty("foreign_keys", "true")
            return sqLiteDataSource
        }
    }
}
