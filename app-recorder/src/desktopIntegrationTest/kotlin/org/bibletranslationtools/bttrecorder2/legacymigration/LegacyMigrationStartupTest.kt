package org.bibletranslationtools.bttrecorder2.legacymigration

import io.reactivex.Observable
import org.bibletranslationtools.otter.common.api.persistence.IAppDirectories
import org.bibletranslationtools.otter.common.api.persistence.config.AppStartupStep
import org.bibletranslationtools.otter.common.data.ProgressStatus
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The migration's graph, as the Android recorder composes it ([legacyMigrationModule] plus a
 * reader and the templates), with the old app's storage and the templates from the repo in place
 * of the device's.
 */
class LegacyMigrationStartupTest {

    private val env = LegacyMigrationEnvironment.create()
    private val old = OldAppStorage(env.scratch("old-app/marker").parentFile)

    init {
        env.koin.loadModules(
            listOf(
                legacyMigrationModule,
                module {
                    single<ILegacyProjectReader> { old.reader() }
                    single<IUlbTemplateFiles> { env.templatesFromDisk }
                }
            )
        )
    }

    @AfterTest
    fun close() {
        old.close()
        env.close()
    }

    @Test
    fun `the migration graph's only startup step is the migration`() {
        assertIs<LegacyMigrationStartupStep>(env.koin.getAll<AppStartupStep>().single())
    }

    @Test
    fun `the startup step migrates, shows its progress, and leaves a summary to tell, once`() {
        oldVerseProject()
        val step = env.koin.getAll<AppStartupStep>().single()

        val statuses = Observable.create<ProgressStatus> { emitter ->
            step.exec(emitter).blockingAwait()
            emitter.onComplete()
        }.toList().blockingGet()

        assertEquals(listOf(0 to 1, 1 to 1), statuses.mapNotNull { LegacyMigrationStartupStep.progressOf(it) })
        assertEquals(1, env.importedProject("jas", "aa").chapterTakes.getValue(1).size)
        val journal = env.koin.get<IAppDirectories>().logsDirectory.resolve(LEGACY_MIGRATION_JOURNAL_FILE)
        assertTrue(journal.isFile, "the journal is among the app's logs")
        val summaries = env.koin.get<ILegacyMigrationSummaries>()
        val summary = assertNotNull(summaries.pending())
        assertEquals(1, summary.chaptersMoved)
        summaries.markReported(summary)
        assertNull(summaries.pending())
    }

    @Test
    fun `with nothing to migrate the step does nothing, and there is nothing to tell`() {
        val step = env.koin.getAll<AppStartupStep>().single()

        Observable.create<ProgressStatus> { emitter ->
            step.exec(emitter).blockingAwait()
            emitter.onComplete()
        }.blockingSubscribe()

        assertNull(env.koin.get<ILegacyMigrationSummaries>().pending())
    }

    /** An Afar ULB project of James 1 in the old app, one selected take per verse. */
    private fun oldVerseProject() {
        val anthology = old.anthology("nt")
        val project = old.project(
            target = old.language("aa"),
            book = old.book("jas", 59, anthology),
            version = old.version("ulb"),
            mode = old.mode(anthology, "verse"),
            source = old.language("en")
        )
        val chapter = old.chapter(project, 1)
        (1..3).forEach { verse ->
            val unit = old.unit(project, chapter, verse)
            val name = OldAppStorage.takeName("aa", "ulb", 59, "jas", 1, verse..verse, 1)
            LegacyTakeWav.write(
                old.takeFile("aa", "ulb", "jas", 1, name), 400 + verse, seed = verse,
                cues = listOf("$verse" to 0),
                metadataJson = LegacyTakeWav.metadataJson("verse", verse, verse)
            )
            old.choose(unit, old.take(unit, 1, name))
        }
    }
}
