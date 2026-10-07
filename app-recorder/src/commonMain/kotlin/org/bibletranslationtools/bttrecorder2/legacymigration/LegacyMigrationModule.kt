package org.bibletranslationtools.bttrecorder2.legacymigration

import org.bibletranslationtools.otter.common.api.persistence.IAppDirectories
import org.bibletranslationtools.otter.common.api.persistence.ITempFileProvider
import org.bibletranslationtools.otter.common.api.persistence.config.AppStartupStep
import org.koin.core.module.dsl.factoryOf
import org.koin.dsl.bind
import org.koin.dsl.module

/** The migration journal's file, among the app's logs so it travels with them. */
const val LEGACY_MIGRATION_JOURNAL_FILE = "legacy_migration_journal.json"

/**
 * What tells the user, once, what the migration did. Only a graph with [legacyMigrationModule]
 * has one; elsewhere there is nothing to tell.
 */
interface ILegacyMigrationSummaries {
    /** What the user hasn't been told yet, or null. */
    fun pending(): MigrationSummary?

    /** Records that the user has been told [summary]. */
    fun markReported(summary: MigrationSummary)
}

class JournalMigrationSummaries(private val journal: MigrationJournal) : ILegacyMigrationSummaries {
    override fun pending(): MigrationSummary? = MigrationSummaries.pending(journal.load())

    override fun markReported(summary: MigrationSummary) =
        journal.write(MigrationSummaries.markReported(journal.load(), summary))
}

/**
 * The legacy migration's graph. Only a platform with old BTT-Recorder data adds it (the Android
 * recorder), together with its own [ILegacyProjectReader] and [IUlbTemplateFiles]. No other graph
 * has it, so nothing else runs the migration: its startup step is the only [AppStartupStep] it binds.
 */
val legacyMigrationModule = module {
    factoryOf(::InstallUlbTemplates)
    factoryOf(::LoadTemplateChunks)
    factoryOf(::PlanLegacyMigration)
    factoryOf(::CompileLegacyChapterTake)
    factoryOf(::WriteMigrationBackup)
    factoryOf(::ImportMigrationBackup)
    single { MigrationJournal(get<IAppDirectories>().logsDirectory.resolve(LEGACY_MIGRATION_JOURNAL_FILE)) }
    factory {
        val tempFiles = get<ITempFileProvider>()
        MigrateLegacyProjects(
            reader = get(),
            installTemplates = get(),
            loadTemplateChunks = get(),
            planner = get(),
            writeBackup = get(),
            importBackup = get(),
            languages = get(),
            journal = get(),
            tempFiles = tempFiles,
            freeBytes = { tempFiles.tempDirectory.usableSpace }
        )
    }
    factory<ILegacyMigrationSummaries> { JournalMigrationSummaries(get()) }
    factoryOf(::LegacyMigrationStartupStep) bind AppStartupStep::class
}
