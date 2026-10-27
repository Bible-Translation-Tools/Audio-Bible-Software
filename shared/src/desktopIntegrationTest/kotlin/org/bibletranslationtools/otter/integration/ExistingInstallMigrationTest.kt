package org.bibletranslationtools.otter.integration

import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.domain.resourcecontainer.structure.VerseRange
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Step 13: bringing an install from before source editions into the new model. */
class ExistingInstallMigrationTest {

    private var env: IntegrationEnvironment? = null

    @AfterTest
    fun tearDown() {
        env?.close()
        env = null
    }

    private fun environment() = IntegrationEnvironment.create().also { env = it }

    @Test
    fun `a source merged in place takes the label and dates of the manifest on disk`() {
        val environment = environment().import("en_ulb.zip")
        val source = environment.sourceEditions().single()
        environment.db.resourceMetadataDao.update(source.copy(version = "11", issued = "2016-01-01"))

        val findings = environment.reconcileEditionLabels.reconcile()

        val relabelled = environment.db.resourceMetadataDao.fetchById(source.id)!!
        assertEquals(source.version, relabelled.version)
        assertEquals(source.issued, relabelled.issued)
        assertEquals(listOf("11" to source.version), findings.map { it.storedVersion to it.manifestVersion })
        assertTrue(findings.single().relabelled)
    }

    @Test
    fun `a source that agrees with its manifest is left alone`() {
        val environment = environment().import("en_ulb.zip")

        assertEquals(emptyList(), environment.reconcileEditionLabels.reconcile())
    }

    @Test
    fun `projects holding a source's empty verses are reported, with which are recorded`() {
        val environment = environment()
        environment.import(environment.bundledSource("en_ulb.zip"))
        val source = environment.sourceEditions().single()
        val project = environment.createProject(
            environment.sourceBook("act", source.id), environment.language("fr"),
            mode = ProjectMode.NARRATION, deriveProjectFromVerses = true
        )
        environment.addPhantomVerse(source.id, project, sort = 19, verse = 41)
        environment.recordTake(project, sort = 19, verse = 41)

        val report = environment.auditSourceStructure.auditProjects().single()

        assertEquals("act", report.book)
        assertEquals("fr", report.targetLanguage)
        assertEquals(listOf("act_19:41"), report.phantomVerses)
        assertEquals(listOf("act_19:41"), report.recordedVerses)
    }

    @Test
    fun `recordings pick the installed edition whose verses they match`() {
        val environment = environment().import("en_ulb.zip")
        // A project keeps the older edition installed once the newer one arrives.
        environment.createProject(environment.sourceBook("jud"), environment.language("fr"), deriveProjectFromVerses = true)
        environment.import(environment.newerDatesOf(environment.bundledSource("en_ulb.zip"), "24-07"))
        val older = environment.sourceEditions().single { it.version != "24-07" }
        val newer = environment.sourceEditions().single { it.version == "24-07" }
        val matcher = environment.matchEditionToRecordings

        val with41 = runBlocking { matcher.rank("en", "ulb", "act", mapOf(19 to listOf(VerseRange(40), VerseRange(41)))) }
        assertEquals(listOf(older.id, newer.id), with41.map { it.edition.id }, "only the older edition has 19:41")
        assertTrue(with41.first().exact)
        assertEquals(listOf("19:41"), with41.last().unmatched)

        val upTo40 = runBlocking { matcher.rank("en", "ulb", "act", mapOf(19 to (1..40).map { VerseRange(it) })) }
        assertEquals(newer.id, upTo40.first().edition.id, "both fit, so the newest")
        assertTrue(upTo40.all { it.exact })

        val bridged = runBlocking { matcher.rank("en", "ulb", "act", mapOf(19 to listOf(VerseRange(1, 3)))) }
        assertEquals(listOf("19:1-3"), bridged.first().unmatched, "neither edition bridges 1-3")
    }
}
