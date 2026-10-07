package org.bibletranslationtools.bttrecorder2.legacymigration

import org.bibletranslationtools.otter.common.api.persistence.IAppDirectories
import org.bibletranslationtools.otter.common.api.persistence.ITempFileProvider
import org.bibletranslationtools.otter.common.api.persistence.config.AppStartupStep
import org.koin.core.module.dsl.factoryOf
import org.koin.dsl.bind
import org.koin.dsl.module
import java.io.OutputStream

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
 * The archive offer (M10): before the old recordings are deleted, the user may save an archive of
 * them, or decline. Only a graph with [legacyMigrationModule] has one.
 */
interface ILegacyArchive {
    /** Whether to offer it now: something waits to be deleted, and the user hasn't chosen. */
    fun offerNeeded(): Boolean

    /** Writes the archive to [destination], and closes it. */
    suspend fun save(destination: OutputStream): WriteLegacyArchive.Result

    /** Records the user's choice, then deletes what may now go. */
    suspend fun choose(saved: Boolean): DeleteLegacyProjects.Result
}

class JournalLegacyArchive(
    private val journal: MigrationJournal,
    private val writeArchive: WriteLegacyArchive,
    private val deleteProjects: DeleteLegacyProjects
) : ILegacyArchive {
    override fun offerNeeded(): Boolean = journal.load().let { data ->
        data.archive == null && data.projects.values.any { it.state.deletable }
    }

    override suspend fun save(destination: OutputStream) = writeArchive.execute(destination)

    override suspend fun choose(saved: Boolean): DeleteLegacyProjects.Result {
        val data = journal.load()
        journal.write(data.copy(archive = ArchiveChoice(saved, System.currentTimeMillis())))
        return deleteProjects.execute()
    }
}

/**
 * The legacy migration's graph. Only a platform with old BTT-Recorder data adds it (the Android
 * recorder), together with its own [ILegacyStorage], [ILegacyProjectReader] and [IUlbTemplateFiles]. No other graph
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
    factoryOf(::WriteLegacyArchive)
    factory { DeleteLegacyProjects(get(), get(), get()) }
    factory<ILegacyArchive> { JournalLegacyArchive(get(), get(), get()) }
    factoryOf(::LegacyMigrationStartupStep) bind AppStartupStep::class
}
