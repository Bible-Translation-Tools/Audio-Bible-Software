@file:OptIn(InternalResourceApi::class)

package org.bibletranslationtools.otter.database

import android.content.Context
import com.readystatesoftware.sqliteasset.SQLiteAssetHelper
import org.bibletranslationtools.otter.common.api.persistence.AppDatabase
import org.bibletranslationtools.otter.common.api.persistence.CREATION_SCRIPT
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.persistence.database.DatabaseMigrator
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
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.InternalResourceApi
import org.jetbrains.compose.resources.Resource
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

class AndroidAppDatabase(
    context: Context,
    databaseFile: File,
    directoryProvider: IDirectoryProvider
): AppDatabase {

    override val dsl: DSLContext
    private val connection: Connection

    init {
        Class.forName("org.sqldroid.SQLDroidDriver").newInstance()

        connection = DriverManager.getConnection("jdbc:sqlite:" + SQLiteAssetHelper(context, "tr.sqlite", null, 14).writableDatabase.path)
        dsl = DSL.using(connection, SQLDialect.SQLITE)

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

    override val installedEntityDao =
        org.bibletranslationtools.otter.common.persistence.database.daos.InstalledEntityDao(dsl)
    override val translationDao = TranslationDao(dsl)
    override val versificationDao = VersificationDao(dsl)
    override val workbookTypeDao = WorkbookTypeDao(dsl)
    override val workbookDescriptorDao = WorkbookDescriptorDao(dsl)
    override val checkingStatusDao = CheckingStatusDao(dsl)
}