package org.bibletranslationtools.otter.integration

import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.otter.common.domain.collections.EditionRelation
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Orature's projects are whole Bibles: moving one moves every book (O1-Q1). The older edition is
 * the English ULB fixture (Acts 19 has 41 verses), the newer the bundled ULB relabelled 24-07.
 */
class UpgradeProjectEditionTest {

    private var env: IntegrationEnvironment? = null

    @AfterTest
    fun tearDown() {
        env?.close()
        env = null
    }

    @Test
    fun `every book moves, and a recorded chapter with a new structure is held back`() {
        val environment = IntegrationEnvironment.create().also { env = it }
        environment.import("en_ulb.zip")
        val older = environment.sourceEditions().single()
        environment.createAllBooks(older.id, environment.language("fr"))
        val books = environment.derivedProjects()
        val acts = books.single { it.slug == "act" }
        environment.recordTake(acts, sort = 19, verse = 41)
        environment.import(environment.newerDatesOf(environment.bundledSource("en_ulb.zip"), "24-07"))
        val newer = environment.editionMetadata(environment.sourceEditions().single { it.version == "24-07" }.id)
        val use = environment.upgradeProjectEdition

        val choices = runBlocking { use.choices(listOf(environment.editionMetadata(older.id))) }
        assertEquals(listOf(newer.id to true), choices.map { it.edition.id to it.newer })

        val started = System.currentTimeMillis()
        val plan = runBlocking { use.plan(books.map { it.id }, newer) }
        val planned = System.currentTimeMillis() - started
        assertEquals(books.size, plan.books.size)
        assertEquals(emptyList(), plan.unavailable)
        assertEquals(mapOf("act" to listOf(19)), plan.heldBack)

        var progress = 0 to 0
        runBlocking { use.apply(plan) { done, total -> progress = done to total } }

        assertEquals(books.size to books.size, progress)
        assertTrue(books.all { environment.descriptorSourceEdition(it) == newer.id }, "every book is on the newer edition")
        assertEquals(older.id, environment.structureEdition(acts, 19))
        assertTrue(environment.sourceEditions().any { it.id == older.id }, "kept for the held-back chapter")
        println("Planned ${books.size} books in $planned ms")
    }

    /** The fixture and the bundled ULB both say 12, issued and modified 2017-11-29, with different text. */
    @Test
    fun `an edition with the same dates is neither newer nor older`() {
        val environment = IntegrationEnvironment.create().also { env = it }
        environment.import("en_ulb.zip")
        val fixture = environment.sourceEditions().single()
        environment.createAllBooks(fixture.id, environment.language("fr"))
        environment.import(environment.bundledSource("en_ulb.zip"))
        val bundled = environment.sourceEditions().single { it.id != fixture.id }

        val choices = runBlocking { environment.upgradeProjectEdition.choices(listOf(environment.editionMetadata(fixture.id))) }

        assertEquals(listOf(bundled.id to EditionRelation.SAME_DATES), choices.map { it.edition.id to it.relation })
        assertTrue(choices.none { it.newer })
    }
}
