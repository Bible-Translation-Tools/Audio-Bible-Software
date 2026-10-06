package org.bibletranslationtools.bttrecorder2.legacymigration

import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.bttrecorder2.legacymigration.InstallUlbTemplates.Outcome
import org.bibletranslationtools.bttrecorder2.legacymigration.WriteMigrationBackup.ChapterTakeOutcome.LeftOut
import org.bibletranslationtools.bttrecorder2.legacymigration.WriteMigrationBackup.ChapterTakeOutcome.Written
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WriteMigrationBackupTest {

    private val env = LegacyMigrationEnvironment.create()

    @AfterTest
    fun close() = env.close()

    /** James 1's chunks, as the old app divided it. */
    private val chunks = LegacyMigrationEnvironment.legacyChunks.getValue("jas").getValue(1)

    @Test
    fun `the backup restores as a dialect project on the template's edition, with its chapter take selected`() = runBlocking {
        val edition = installed(UlbTemplate.CHUNKS)
        val project = project(ChapterPlan(1, listOf(plan(1, "reg", true, take(chunks[0], 1000), take(chunks[1], 800)))))

        val result = env.writeMigrationBackup().execute(project, edition, env.language("aa"), env.scratch("backups/jas.zip"))

        val written = assertIs<Written>(result.takes.single())
        assertTrue(written.selected)
        assertEquals(1800, written.totalFrames)
        assertEquals(ImportResult.SUCCESS, env.importBackup(assertNotNull(result.backup)))
        val imported = env.importedProject("jas", "aa")
        assertEquals(edition.id, imported.editionId, "bound to the template's edition")
        assertEquals(ProjectMode.DIALECT, imported.mode)
        val take = imported.chapterTakes.getValue(1).single()
        assertTrue(take.selected)
        assertEquals(written.markers, markersIn(take.file))
        assertEquals(listOf(edition.id), env.sourceEditions().map { it.id }, "the embedded template isn't installed again")
    }

    @Test
    fun `the backup is laid out like one made here, with the template embedded and named in its edition record`() = runBlocking {
        val edition = installed(UlbTemplate.CHUNKS)
        val project = project(ChapterPlan(1, listOf(plan(1, "reg", true, take(chunks[0], 500)))))

        val backup = assertNotNull(
            env.writeMigrationBackup().execute(project, edition, env.language("aa"), env.scratch("backups/jas.zip")).backup
        )

        ZipFile(backup).use { zip ->
            fun text(path: String) = zip.getInputStream(assertNotNull(zip.getEntry(path), path)).use { it.readBytes().decodeToString() }
            assertNotNull(zip.getEntry(".apps/orature/takes/c01/aa_reg_jas_c01_meta_t1.wav"), "the chapter take")
            assertEquals("c01/aa_reg_jas_c01_meta_t1.wav", text(".apps/orature/selected.txt").trim())
            assertTrue("DIALECT" in text(".apps/orature/project_mode.json"))
            val embedded = zip.getInputStream(assertNotNull(zip.getEntry(".apps/orature/source/en_ulb.zip"))).use { it.readBytes() }
            assertContentEquals(LegacyMigrationEnvironment.templateFile(UlbTemplate.CHUNKS).readBytes(), embedded)
            val record = text(".apps/bibletranslationtools/source_editions.json")
            assertTrue("\"book\":\"jas\"" in record.replace(Regex("\\s"), ""), record)
            assertTrue(".apps/orature/source/en_ulb.zip" in record, record)
            val manifest = text("manifest.yaml")
            assertTrue("Ana" in manifest && "Ben" in manifest, "contributors in\n$manifest")
            assertTrue(Regex("""identifier: ["']?jas["']?\s""").containsMatchIn(manifest), "the book in\n$manifest")
        }
    }

    @Test
    fun `each version label is its own chapter take, and only the planned one is selected`() = runBlocking {
        val edition = installed(UlbTemplate.CHUNKS)
        val reg = plan(1, "reg", true, take(chunks[0], 700), take(chunks[1], 700))
        val ulb = plan(2, "ulb", false, take(chunks[0], 400))
        val project = project(ChapterPlan(1, listOf(reg, ulb)))

        val result = env.writeMigrationBackup().execute(project, edition, env.language("aa"), env.scratch("backups/jas.zip"))

        val (regTake, ulbTake) = result.takes.map { assertIs<Written>(it) }
        assertEquals(listOf(true, false), listOf(regTake.selected, ulbTake.selected))
        assertEquals(ImportResult.SUCCESS, env.importBackup(assertNotNull(result.backup)))
        val takes = env.importedProject("jas", "aa").chapterTakes.getValue(1)
        assertEquals(listOf(1, 2), takes.map { it.number })
        assertEquals(regTake.markers, markersIn(takes.single { it.selected }.file))
        assertEquals(ulbTake.markers, markersIn(takes.single { !it.selected }.file))
    }

    @Test
    fun `when the planned take can't be compiled, the most complete one that could is selected`() = runBlocking {
        val edition = installed(UlbTemplate.CHUNKS)
        val broken = plan(1, "reg", true, take(chunks[0], 300).also { it.file.writeText("not a recording") })
        val shorter = plan(2, "udb", false, take(chunks[0], 300))
        val longer = plan(3, "ulb", false, take(chunks[0], 300), take(chunks[1], 300))
        val project = project(ChapterPlan(1, listOf(broken, shorter, longer)))

        val result = env.writeMigrationBackup().execute(project, edition, env.language("aa"), env.scratch("backups/jas.zip"))

        val (first, second, third) = result.takes
        assertIs<MigrationProblem.AudioUnreadable>(assertIs<LeftOut>(first).problems.single())
        assertFalse(assertIs<Written>(second).selected)
        assertTrue(assertIs<Written>(third).selected)
        assertEquals(ImportResult.SUCCESS, env.importBackup(assertNotNull(result.backup)))
        val takes = env.importedProject("jas", "aa").chapterTakes.getValue(1)
        assertEquals(2, takes.size)
        assertEquals(third.markers, markersIn(takes.single { it.selected }.file))
    }

    @Test
    fun `with nothing usable, no backup is written`() = runBlocking {
        val edition = installed(UlbTemplate.CHUNKS)
        val project = project(ChapterPlan(1, listOf(plan(1, "reg", true, take(chunks[0], 300).also { it.file.writeText("not audio") }))))
        val destination = env.scratch("backups/jas.zip")

        val result = env.writeMigrationBackup().execute(project, edition, env.language("aa"), destination)

        assertNull(result.backup)
        assertIs<LeftOut>(result.takes.single())
        assertFalse(destination.exists())
    }

    @Test
    fun `restoring a rewritten backup again adds no take`() = runBlocking {
        val edition = installed(UlbTemplate.CHUNKS)
        val project = project(ChapterPlan(1, listOf(plan(1, "reg", true, take(chunks[0], 600), take(chunks[1], 600)))))
        val write = env.writeMigrationBackup()

        val once = write.execute(project, edition, env.language("aa"), env.scratch("backups/once.zip"))
        assertEquals(ImportResult.SUCCESS, env.importBackup(assertNotNull(once.backup)))
        val twice = write.execute(project, edition, env.language("aa"), env.scratch("backups/twice.zip"))
        assertEquals(ImportResult.SUCCESS, env.importBackup(assertNotNull(twice.backup)))

        val take = env.importedProject("jas", "aa").chapterTakes.getValue(1).single()
        assertTrue(take.selected)
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────

    private suspend fun installed(template: UlbTemplate): ResourceMetadata =
        assertIs<Outcome.Installed>(env.installUlbTemplates().execute(setOf(template)).getValue(template)).edition

    private var nextId = 1

    /** A finished legacy chunk take of [verses], as the old app wrote it. */
    private fun take(verses: IntRange, frames: Int): LegacyTake {
        val id = nextId++
        val file = env.scratch("legacy/take-$id.wav")
        LegacyTakeWav.write(
            file, frames, seed = id,
            cues = listOf("${verses.first}" to 0),
            metadataJson = LegacyTakeWav.metadataJson("chunk", verses.first, verses.last)
        )
        return LegacyTake(id, 1, 0, null, file, true, verses, LegacyTakeMetadata(LegacyChunking.CHUNKS, verses))
    }

    private fun plan(legacyProjectId: Int, label: String, selected: Boolean, vararg takes: LegacyTake) =
        ChapterTakePlan(legacyProjectId, label, selected, takes.map { ChapterPiece(it.verses!!, it) })

    private fun project(vararg chapters: ChapterPlan) = MigrationProject(
        key = MigrationProjectKey("aa", "jas", UlbTemplate.CHUNKS),
        book = LegacyBook("jas", 59),
        contributors = listOf("Ana", "Ben"),
        legacyProjectIds = chapters.flatMap { c -> c.takes.map { it.legacyProjectId } }.distinct(),
        chapters = chapters.toList()
    )

    private fun markersIn(file: File) = OratureAudioFile(file).getMarkers().sortedBy { it.location }
}
