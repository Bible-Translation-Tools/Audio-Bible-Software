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
 * The English ULB fixture and its sibling (the bundled ULB under the fixture's labels) both say
 * `version: '12'`, but the sibling folds Acts 19:41 into 19:40 and 2 Cor 13:14 into 13:13, and its
 * Isaiah 50 differs too. Their fingerprints must tell them apart, and must point at exactly those
 * chapters.
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

    /** The newer "v12", the fixture's sibling. */
    private fun siblingFingerprint() = fingerprintAfterImport { import(siblingUlb()) }

    @Test
    fun `import stores a fingerprint with the detected versification`() {
        val fingerprint = siblingFingerprint()

        assertEquals("eng", fingerprint.detectedVersification)
        assertEquals(1189, fingerprint.chapters.size, "one per chapter of the 66 books")
    }

    @Test
    fun `the two v12 English zips are different editions`() {
        val fixture = fixtureFingerprint()
        val sibling = siblingFingerprint()

        assertNotEquals(fixture.structureFingerprint, sibling.structureFingerprint)
        assertNotEquals(fixture.textFingerprint, sibling.textFingerprint)
    }

    @Test
    fun `their structure differs in exactly Acts 19, 2 Corinthians 13 and Isaiah 50`() {
        val fixture = fixtureFingerprint().chapters.associateBy { it.chapterSlug }
        val sibling = siblingFingerprint().chapters.associateBy { it.chapterSlug }

        val differing = fixture.keys.filter { fixture[it]?.structureHash != sibling[it]?.structureHash }

        assertEquals(listOf("2co_13", "act_19", "isa_50"), differing.sorted())
    }

    @Test
    fun `the backfill gives a source without one the fingerprint import would have`() {
        val environment = environment()
        val atImport = assertNotNull(environment.import(environment.siblingUlb()).storedFingerprint())
        environment.clearFingerprint()
        assertNull(environment.storedFingerprint())

        environment.backfillFingerprints()

        assertEquals(atImport, environment.storedFingerprint())
    }
}
