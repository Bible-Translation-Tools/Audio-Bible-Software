package org.bibletranslationtools.otter.integration

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The startup step that re-imports a bundled source when the build ships a different zip for it,
 * run against the real bundled English ULB and the checksums the build generated.
 */
class RefreshBundledSourcesTest {

    private var env: IntegrationEnvironment? = null

    @AfterTest
    fun tearDown() {
        env?.close()
        env = null
    }

    private fun environment() = IntegrationEnvironment.create().also { env = it }

    /** A device with an older edition installed gets the bundled one; they're siblings here, so both stay. */
    @Test
    fun `an installed source gets the bundled edition`() {
        val environment = environment().import("en_ulb.zip")

        environment.refreshBundledSources()

        assertEquals(2, environment.sourceEditions().size)
        assertTrue(environment.bundledSourceIsCurrent("en_ulb"))
    }

    @Test
    fun `a bundled edition already installed is only recorded`() {
        val environment = environment()
        environment.import(environment.bundledSource("en_ulb.zip"))
        assertFalse(environment.bundledSourceIsCurrent("en_ulb"))

        environment.refreshBundledSources()

        assertEquals(1, environment.sourceEditions().size)
        assertTrue(environment.bundledSourceIsCurrent("en_ulb"))
    }

    @Test
    fun `sources with no installed edition are left for on-demand import`() {
        val environment = environment()

        environment.refreshBundledSources()

        assertEquals(0, environment.sourceEditions().size)
        assertFalse(environment.bundledSourceIsCurrent("en_ulb"))
    }
}
