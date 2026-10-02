package org.bibletranslationtools.otter.integration

import org.bibletranslationtools.otter.common.domain.resourcecontainer.EditionFingerprint
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Edition fingerprints stored by a real import, and by the startup backfill.
 *
 * The two English ULB zips in this repo both say `version: '12'`, but the bundled one folds Acts
 * 19:41 into 19:40 and 2 Cor 13:14 into 13:13. Their fingerprints must tell them apart, and must
 * point at exactly those chapters.
 */
class EditionFingerprintImportTest {

    private val environments = mutableListOf<IntegrationEnvironment>()

    @AfterTest
    fun tearDown() = environments.forEach { it.close() }

    private fun environment() = IntegrationEnvironment.create().also { environments.add(it) }

    /** Imports into an environment of its own and reads the fingerprint back. Koin is global, so
     * only one environment can be open at a time. */
    private fun fingerprintAfterImport(import: IntegrationEnvironment.() -> Unit): EditionFingerprint =
        IntegrationEnvironment.create().use { assertNotNull(it.apply(import).storedFingerprint()) }

    /** The older "v12", the test fixture. */
    private fun fixtureFingerprint() = fingerprintAfterImport { import("en_ulb.zip") }

    /** The newer "v12", the one the app bundles. */
    private fun bundledFingerprint() = fingerprintAfterImport { import(bundledSource("en_ulb.zip")) }

    @Test
    fun `import stores a fingerprint with the detected versification`() {
        val fingerprint = bundledFingerprint()

        assertEquals("eng", fingerprint.detectedVersification)
        assertEquals(1189, fingerprint.chapters.size, "one per chapter of the 66 books")
    }

    @Test
    fun `the two v12 English zips are different editions`() {
        val fixture = fixtureFingerprint()
        val bundled = bundledFingerprint()

        assertNotEquals(fixture.structureFingerprint, bundled.structureFingerprint)
        assertNotEquals(fixture.textFingerprint, bundled.textFingerprint)
    }

    @Test
    fun `their structure differs in exactly Acts 19 and 2 Corinthians 13`() {
        val fixture = fixtureFingerprint().chapters.associateBy { it.chapterSlug }
        val bundled = bundledFingerprint().chapters.associateBy { it.chapterSlug }

        val differing = fixture.keys.filter { fixture[it]?.structureHash != bundled[it]?.structureHash }

        assertEquals(listOf("2co_13", "act_19"), differing.sorted())
    }

    @Test
    fun `the backfill gives a source without one the fingerprint import would have`() {
        val environment = environment()
        val atImport = assertNotNull(environment.import(environment.bundledSource("en_ulb.zip")).storedFingerprint())
        environment.clearFingerprint()
        assertNull(environment.storedFingerprint())

        environment.backfillFingerprints()

        assertEquals(atImport, environment.storedFingerprint())
    }
}
