package org.bibletranslationtools.otter.integration

import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.domain.resourcecontainer.RcConstants
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A backup carries its book's source edition and its held-back chapters' editions, and a restore
 * attaches to them (step 11). The older edition is the English ULB fixture (Acts 19 has 41 verses);
 * the newer is the bundled ULB with later dates (Acts 19 has 40).
 */
class BackupEditionRoundTripTest {

    private var env: IntegrationEnvironment? = null

    @AfterTest
    fun tearDown() {
        env?.close()
        env = null
    }

    private val IntegrationEnvironment.older get() = sourceEditions().single { it.issued == "2017-11-29" && it.version == olderVersion }
    private val IntegrationEnvironment.newer get() = sourceEditions().single { it.issued == "2024-07-12" }
    private var olderVersion = ""

    /** The fixture installed and a verse-by-verse French project of Acts on it, with a take on 19:41. */
    private fun setup(newerVersion: String? = null): Pair<IntegrationEnvironment, Collection> {
        val environment = IntegrationEnvironment.create().also { env = it }
        environment.import("en_ulb.zip")
        val older = environment.sourceEditions().single()
        olderVersion = older.version
        val project = environment.createProject(
            environment.sourceBook("act", older.id), environment.language("fr"),
            mode = ProjectMode.NARRATION, deriveProjectFromVerses = true
        )
        environment.recordTake(project, sort = 19, verse = 41)
        environment.import(environment.newerDatesOf(environment.bundledSource("en_ulb.zip"), newerVersion))
        return environment to project
    }

    private fun IntegrationEnvironment.restoredProject(): Collection = derivedProjects().single { it.slug == "act" }

    @Test
    fun `a held-back chapter is restored on its edition, even where that edition wasn't installed`() {
        val (environment, project) = setup()
        runBlocking {
            val upgrade = environment.upgradeBookEdition
            upgrade.apply(upgrade.plan(project.id, environment.editionMetadata(environment.newer.id)))
        }
        val backup = environment.backup(project)
        assertTrue(RcConstants.SOURCE_EDITIONS_FILE in environment.zipEntries(backup))

        // As on another device: the project, and the older edition, gone.
        environment.deleteAllProjects()
        assertTrue(environment.removeEdition(environment.older.id), "the older edition is removed")

        environment.import(backup)

        val restored = environment.restoredProject()
        assertEquals(environment.newer.id, environment.descriptorSourceEdition(restored), "the book is on the newer edition")
        assertEquals(environment.older.id, environment.structureEdition(restored, 19), "chapter 19 is on the older one")
        assertEquals((1..41).map { it to it }, environment.projectVerses(restored, 19))
        assertEquals(listOf(41), environment.versesWithTakes(restored, 19), "the take is back on 41")
        assertNull(environment.structureEdition(restored, 18))
    }

    @Test
    fun `a backup without an edition record attaches to the edition embedded in it`() {
        val (environment, project) = setup()
        val backup = environment.withoutEntries(environment.backup(project), RcConstants.OWN_APP_DIR)

        environment.deleteAllProjects()
        environment.import(backup)

        val restored = environment.restoredProject()
        assertEquals(environment.older.id, environment.descriptorSourceEdition(restored), "attached to the embedded edition (S11-Q3)")
        assertTrue(environment.describeEdition(environment.older.id).updateAvailable)
        assertEquals(listOf(41), environment.versesWithTakes(restored, 19))
    }

    @Test
    fun `Orature finds the book's source where it expects it, and only that`() {
        val (environment, project) = setup()
        runBlocking {
            val upgrade = environment.upgradeBookEdition
            upgrade.apply(upgrade.plan(project.id, environment.editionMetadata(environment.newer.id)))
        }

        val entries = environment.zipEntries(environment.backup(project))

        val orature = entries.filter { it.startsWith(RcConstants.SOURCE_DIR + "/") && File(it).parent == RcConstants.SOURCE_DIR }
        assertEquals(listOf("${RcConstants.SOURCE_DIR}/en_ulb.zip"), orature)
        assertEquals(1, entries.count { it.startsWith(RcConstants.OWN_SOURCE_DIR + "/") && it.endsWith(".zip") }, "the held-back chapter's edition")
    }

    @Test
    fun `a backup that neither records nor embeds its edition takes the closest installed one`() {
        val (environment, project) = setup(newerVersion = "24-07")
        val stripped = environment.withoutEntries(
            environment.withoutEntries(environment.backup(project), RcConstants.OWN_APP_DIR),
            "${RcConstants.SOURCE_DIR}/en_ulb.zip"
        )
        environment.deleteAllProjects()
        assertTrue(environment.removeEdition(environment.older.id), "v12 is removed with the project")

        environment.import(stripped)

        val restored = environment.restoredProject()
        assertEquals(environment.newer.id, environment.descriptorSourceEdition(restored), "no v12, so the newest (S11-Q4)")
        // The newest has no 19:41. The take isn't placed, but its file is kept (and a warning logged).
        assertEquals(emptyList(), environment.versesWithTakes(restored, 19))
        assertTrue(environment.projectDirectory(restored).walk().any { it.name.endsWith("_v41_t1.wav") })
    }
}
