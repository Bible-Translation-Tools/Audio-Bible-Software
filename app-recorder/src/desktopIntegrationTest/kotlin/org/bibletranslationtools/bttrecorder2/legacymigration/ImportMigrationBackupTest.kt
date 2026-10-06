package org.bibletranslationtools.bttrecorder2.legacymigration

import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.bttrecorder2.legacymigration.ImportMigrationBackup.Outcome
import org.bibletranslationtools.bttrecorder2.legacymigration.ImportMigrationBackup.RefusalReason
import org.bibletranslationtools.bttrecorder2.legacymigration.ImportMigrationBackup.VerificationFailure
import org.bibletranslationtools.bttrecorder2.legacymigration.InstallUlbTemplates.Outcome.Installed
import org.bibletranslationtools.bttrecorder2.legacymigration.WriteMigrationBackup.ChapterTakeOutcome.Written
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Importing migration backups on the verse template, and proving they landed. */
class ImportMigrationBackupTest {

    private val env = LegacyMigrationEnvironment.create()

    @AfterTest
    fun close() = env.close()

    @Test
    fun `a migrated project is verified, with every chapter take on the template's edition`() = runBlocking {
        val edition = installedVerses()
        val project = project(
            "jas",
            ChapterPlan(1, listOf(plan(1, "reg", true, 1..3, seed = 0), plan(2, "ulb", false, 1..2, seed = 10))),
            ChapterPlan(2, listOf(plan(1, "reg", true, 1..2, seed = 20)))
        )
        val backup = written(project, edition)

        val outcome = assertIs<Outcome.Verified>(env.importMigrationBackup().execute(project, edition, backup))

        assertEquals("jas", outcome.projectBook.slug)
        assertEquals(3, outcome.added)
        assertEquals(0, outcome.alreadyThere)
        val takes = env.importedProject("jas", "aa").chapterTakes
        assertEquals(listOf(2, 1), listOf(1, 2).map { takes.getValue(it).size })
        assertEquals(listOf(1, 1), listOf(1, 2).map { chapter -> takes.getValue(chapter).count { it.selected } })
    }

    @Test
    fun `migrating again adds no take, and verifies again`() = runBlocking {
        val edition = installedVerses()
        val project = project("jas", ChapterPlan(1, listOf(plan(1, "reg", true, 1..3, seed = 0))))
        val import = env.importMigrationBackup()
        assertIs<Outcome.Verified>(import.execute(project, edition, written(project, edition, "once")))

        val again = assertIs<Outcome.Verified>(import.execute(project, edition, written(project, edition, "again")))

        assertEquals(0, again.added)
        assertEquals(1, again.alreadyThere)
        assertEquals(1, env.importedProject("jas", "aa").chapterTakes.getValue(1).size)
    }

    @Test
    fun `another book in the same language goes into the empty project the first import made for it`() = runBlocking {
        val edition = installedVerses()
        val james = project("jas", ChapterPlan(1, listOf(plan(1, "reg", true, 1..2, seed = 0))))
        val firstJohn = project("1jn", ChapterPlan(1, listOf(plan(2, "reg", true, 1..2, seed = 10))))
        assertIs<Outcome.Verified>(env.importMigrationBackup().execute(james, edition, written(james, edition)))

        val outcome = env.importMigrationBackup().execute(firstJohn, edition, written(firstJohn, edition))

        assertIs<Outcome.Verified>(outcome)
        assertEquals(1, env.importedProject("1jn", "aa").chapterTakes.getValue(1).size)
    }

    @Test
    fun `a project of the book on another edition is refused, and left as it was`() = runBlocking {
        val edition = installedVerses()
        assertEquals(ImportResult.SUCCESS, env.installAsNewSource(env.lookAlikeOf(UlbTemplate.VERSES)))
        val other = env.sourceEditions().single { it.id != edition.id }
        env.createDialectProject(env.sourceBook("jas", other.id), env.language("aa"))
        val project = project("jas", ChapterPlan(1, listOf(plan(1, "reg", true, 1..2, seed = 0))))

        val outcome = env.importMigrationBackup().execute(project, edition, written(project, edition))

        val refused = assertIs<RefusalReason.OnAnotherEdition>(assertIs<Outcome.Refused>(outcome).reason)
        assertEquals(other.id, refused.editionId)
        assertTrue(env.importedProject("jas", "aa").chapterTakes.isEmpty(), "nothing was imported into it")
    }

    @Test
    fun `a project of the book holding other recordings is refused`() = runBlocking {
        val edition = installedVerses()
        val first = project("jas", ChapterPlan(1, listOf(plan(1, "reg", true, 1..2, seed = 0))))
        assertIs<Outcome.Verified>(env.importMigrationBackup().execute(first, edition, written(first, edition, "first")))
        val second = project("jas", ChapterPlan(1, listOf(plan(2, "reg", true, 1..2, seed = 50))))

        val outcome = env.importMigrationBackup().execute(second, edition, written(second, edition, "second"))

        val refused = assertIs<RefusalReason.HoldsOtherRecordings>(assertIs<Outcome.Refused>(outcome).reason)
        assertEquals(1, refused.count)
        assertEquals(1, env.importedProject("jas", "aa").chapterTakes.getValue(1).size)
    }

    @Test
    fun `a chapter take that didn't land fails verification`() = runBlocking<Unit> {
        val edition = installedVerses()
        val project = project("jas", ChapterPlan(1, listOf(plan(1, "reg", true, 1..2, seed = 0))))
        val backup = written(project, edition)
        // As if the import had dropped it: the take it should find isn't the one in the backup.
        val dropped = backup.copy(takes = backup.takes.map { (it as Written).copy(checksum = "not-in-the-backup") })

        val outcome = env.importMigrationBackup().execute(project, edition, dropped)

        assertIs<VerificationFailure.TakeMissing>(assertIs<Outcome.NotVerified>(outcome).failures.single())
    }

    @Test
    fun `a chapter take that landed unselected fails verification`() = runBlocking<Unit> {
        val edition = installedVerses()
        val project = project("jas", ChapterPlan(1, listOf(plan(1, "reg", true, 1..2, seed = 0), plan(2, "ulb", false, 1..1, seed = 10))))
        val backup = written(project, edition)
        // As if the import had selected the other take: the one it should have selected is reported unselected.
        val swapped = backup.copy(takes = backup.takes.map { (it as Written).copy(selected = !it.selected) })

        val outcome = env.importMigrationBackup().execute(project, edition, swapped)

        assertIs<VerificationFailure.TakeNotSelected>(assertIs<Outcome.NotVerified>(outcome).failures.single())
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────

    private suspend fun installedVerses(): ResourceMetadata =
        assertIs<Installed>(env.installUlbTemplates().execute(setOf(UlbTemplate.VERSES)).getValue(UlbTemplate.VERSES)).edition

    private suspend fun written(project: MigrationProject, edition: ResourceMetadata, name: String = "backup") =
        env.writeMigrationBackup().execute(project, edition, env.language("aa"), env.scratch("backups/${project.key.book}-$name.zip"))

    private var nextId = 1

    /** A chapter take of [verses], one finished verse take each, as the old app wrote them. */
    private fun plan(legacyProjectId: Int, label: String, selected: Boolean, verses: IntRange, seed: Int) =
        ChapterTakePlan(legacyProjectId, label, selected, verses.map { verse -> ChapterPiece(verse..verse, take(verse, seed)) })

    private fun take(verse: Int, seed: Int): LegacyTake {
        val id = nextId++
        val file = env.scratch("legacy/take-$id.wav")
        LegacyTakeWav.write(
            file, frames = 400 + verse * 10, seed = seed + id,
            cues = listOf("$verse" to 0),
            metadataJson = LegacyTakeWav.metadataJson("verse", verse, verse)
        )
        return LegacyTake(id, 1, 0, null, file, true, verse..verse, LegacyTakeMetadata(LegacyChunking.VERSE_BY_VERSE, verse..verse))
    }

    private fun project(book: String, vararg chapters: ChapterPlan) = MigrationProject(
        key = MigrationProjectKey("aa", book, UlbTemplate.VERSES),
        book = LegacyBook(book, 0),
        contributors = listOf("Ana"),
        legacyProjectIds = chapters.flatMap { c -> c.takes.map { it.legacyProjectId } }.distinct(),
        chapters = chapters.toList()
    )
}
