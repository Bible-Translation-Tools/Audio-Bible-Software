package org.bibletranslationtools.otter.integration

import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.domain.collections.BookUpgradePlan
import org.bibletranslationtools.otter.common.domain.collections.ChapterOutcome
import org.bibletranslationtools.otter.common.domain.resourcecontainer.structure.VerseRange
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Moving a project of Acts from the older English ULB fixture (Acts 19 has 41 verses) to a newer
 * edition made from the bundled ULB (Acts 19 has 40: 41 is folded into 40) with later dates.
 */
class UpgradeBookEditionTest {

    private var env: IntegrationEnvironment? = null

    @AfterTest
    fun tearDown() {
        env?.close()
        env = null
    }

    private class Setup(val environment: IntegrationEnvironment, val project: Collection)

    /** The fixture installed, a verse-by-verse French project of Acts on it, and the newer edition installed. */
    private fun setup(newerVersion: String? = null): Setup {
        val environment = IntegrationEnvironment.create().also { env = it }
        environment.import("en_ulb.zip")
        val older = environment.sourceEditions().single()
        val project = environment.createProject(
            environment.sourceBook("act", older.id), environment.language("fr"), deriveProjectFromVerses = true
        )
        environment.import(environment.newerDatesOf(environment.bundledSource("en_ulb.zip"), newerVersion))
        return Setup(environment, project)
    }

    private val Setup.older get() = environment.sourceEditions().first()
    private val Setup.newer get() = environment.sourceEditions().single { it.issued == "2024-07-12" }

    private fun Setup.plan(): BookUpgradePlan = runBlocking {
        val newer = environment.sourceEditions().single { it.issued == "2024-07-12" }
        val edition = environment.editionMetadata(newer.id)
        environment.upgradeBookEdition.plan(project.id, edition)
    }

    private fun Setup.upgrade() = runBlocking { environment.upgradeBookEdition.apply(plan()) }

    private fun BookUpgradePlan.chapter(sort: Int) = chapters.single { it.sort == sort }

    @Test
    fun `an unrecorded chapter with a new structure adopts it, the others are unchanged or reworded`() {
        val plan = setup().plan()

        assertEquals(ChapterOutcome.ADOPTS, plan.chapter(19).outcome)
        assertTrue(plan.chapters.filter { it.sort != 19 }.all { it.outcome in setOf(ChapterOutcome.UNCHANGED, ChapterOutcome.TEXT_ONLY) })
    }

    @Test
    fun `a recorded chapter with a new structure is held back`() {
        val setup = setup()
        setup.environment.addTake(setup.project, sort = 19, verse = 41)

        assertEquals(ChapterOutcome.HELD_BACK, setup.plan().chapter(19).outcome)
    }

    @Test
    fun `adopting gives the chapter the new verses and moves deleted takes with their verse`() {
        val setup = setup()
        val deletedOn41 = setup.environment.addTake(setup.project, sort = 19, verse = 41, deleted = true)

        setup.upgrade()

        val verses = setup.environment.projectVerses(setup.project, 19)
        assertEquals((1..40).map { it to it }, verses, "41 is gone")
        val take = setup.environment.take(deletedOn41)
        assertEquals(40, setup.environment.content(take.contentFk).start, "the deleted take followed 41 into 40")
        assertTrue(File(java.net.URI("file:" + take.filepath)).isFile, "its file is untouched")
    }

    @Test
    fun `the book, its chapters and its descriptor point at the new edition`() {
        val setup = setup()

        setup.upgrade()

        val env = setup.environment
        val newer = setup.newer
        assertEquals(newer.id, env.derivedFromOf(env.projectBookRow(setup.project).dublinCoreFk!!))
        assertEquals(newer.id, env.sourceCollectionEdition(env.projectBookRow(setup.project).sourceFk!!))
        assertEquals(newer.id, env.sourceCollectionEdition(env.projectChapter(setup.project, 19).sourceFk!!))
        assertEquals(newer.id, env.descriptorSourceEdition(setup.project))
        assertNull(env.structureEdition(setup.project, 19), "on the book's edition")
    }

    @Test
    fun `a held-back chapter keeps its verses and plays the newer edition's audio lined up`() {
        val setup = setup()
        setup.environment.addTake(setup.project, sort = 19, verse = 41)
        val older = setup.older

        setup.upgrade()

        val env = setup.environment
        assertEquals((1..41).map { it to it }, env.projectVerses(setup.project, 19))
        assertEquals(older.id, env.structureEdition(setup.project, 19))
        assertTrue(env.sourceEditions().any { it.id == older.id }, "the older edition stays for the held-back chapter")
        val alignment = env.referenceAlignment
        assertEquals(40, alignment.referenceStart(setup.project.id, 19, VerseRange(41)), "41 plays the newer 40")
        assertEquals(40, alignment.referenceStart(setup.project.id, 19, VerseRange(40)))
        assertEquals(39, alignment.referenceStart(setup.project.id, 19, VerseRange(39)))
        assertEquals(5, alignment.referenceStart(setup.project.id, 18, VerseRange(5)), "other chapters follow the book")
    }

    @Test
    fun `when nothing is held back the superseded older edition is removed`() {
        val setup = setup()
        val older = setup.older

        setup.upgrade()

        assertFalse(setup.environment.sourceEditions().any { it.id == older.id })
        assertFalse(File(older.path).exists())
    }

    /** A chapter-mode project with chunks marked in Acts 19 but nothing recorded (S8-Q4, S8-Q7). */
    @Test
    fun `an unrecorded chunked chapter adopts and has its chunks reset`() {
        val environment = IntegrationEnvironment.create().also { env = it }
        environment.import("en_ulb.zip")
        val older = environment.sourceEditions().single()
        val project = environment.createProject(environment.sourceBook("act", older.id), environment.language("fr"))
        environment.addChunk(project, sort = 19, start = 35, end = 41)
        environment.import(environment.newerDatesOf(environment.bundledSource("en_ulb.zip")))
        val setup = Setup(environment, project)

        val plan = setup.plan()
        assertEquals(ChapterOutcome.ADOPTS, plan.chapter(19).outcome)
        assertTrue(plan.chapter(19).chunksReset)

        setup.upgrade()

        assertEquals(0, environment.chunkCount(project, 19))
    }

    /** Same label: both editions' legacy folder is the same one, so the project stays in it. */
    @Test
    fun `a project in a legacy folder stays when the new edition has the same label`() {
        val setup = setup()
        val legacy = setup.environment.moveProjectToLegacyFolder(setup.project)

        setup.upgrade()

        assertTrue(legacy.isDirectory)
    }

    @Test
    fun `a project in a legacy folder moves to the current layout with its take paths`() {
        val setup = setup(newerVersion = "24-07")
        val legacy = setup.environment.moveProjectToLegacyFolder(setup.project)
        val takeId = setup.environment.addTake(setup.project, sort = 1, verse = 1)
        assertTrue(setup.environment.take(takeId).filepath.contains("/v12/"), "the take starts in the legacy folder")

        setup.upgrade()

        val take = setup.environment.take(takeId)
        assertFalse(take.filepath.contains("/v12/"), "take path ${take.filepath}")
        assertTrue(File(java.net.URI("file:" + take.filepath)).isFile, "the take file moved with the folder")
        assertFalse(legacy.exists(), "the legacy folder is gone")
    }
}
