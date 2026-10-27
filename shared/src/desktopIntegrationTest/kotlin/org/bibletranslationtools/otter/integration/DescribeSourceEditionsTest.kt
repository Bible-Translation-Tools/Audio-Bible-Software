package org.bibletranslationtools.otter.integration

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the app shows about a project's source edition: a code only when needed, and updates. */
class DescribeSourceEditionsTest {

    private var env: IntegrationEnvironment? = null

    @AfterTest
    fun tearDown() {
        env?.close()
        env = null
    }

    private fun environment() = IntegrationEnvironment.create().also { env = it }

    @Test
    fun `a single edition shows no code and no update`() {
        val environment = environment().import("en_ulb.zip")

        val summary = environment.describeEdition(environment.sourceEditions().single().id)

        assertNull(summary.distinguishingCode)
        assertFalse(summary.updateAvailable)
    }

    /** The two English "v12" zips share label and issued date, so only a code tells them apart. */
    @Test
    fun `look-alike editions each show their code`() {
        val environment = environment().import("en_ulb.zip")
        environment.import(environment.bundledSource("en_ulb.zip"))
        val (fixture, bundled) = environment.sourceEditions()

        val fixtureCode = environment.describeEdition(fixture.id).distinguishingCode
        val bundledCode = environment.describeEdition(bundled.id).distinguishingCode

        assertNotNull(fixtureCode)
        assertNotNull(bundledCode)
        assertTrue(fixtureCode != bundledCode)
        assertFalse(environment.describeEdition(fixture.id).updateAvailable, "siblings aren't updates")
    }

    @Test
    fun `an edition with a newer one installed has an update available`() {
        val environment = environment().import("en_ulb.zip")
        val older = environment.sourceEditions().single()
        // A project keeps the older edition installed when the newer one arrives.
        environment.createProject(environment.sourceBook("jud", older.id), environment.language("fr"))
        environment.import(environment.newerEdition("en_ulb.zip"))
        val newer = environment.sourceEditions().single { it.id != older.id }

        val summary = environment.describeEdition(older.id)

        assertTrue(summary.updateAvailable)
        assertEquals(newer.id, summary.newerEdition?.id)
        assertFalse(environment.describeEdition(newer.id).updateAvailable)
    }
}
