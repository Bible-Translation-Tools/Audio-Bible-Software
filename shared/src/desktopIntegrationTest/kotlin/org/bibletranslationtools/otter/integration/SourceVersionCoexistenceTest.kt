package org.bibletranslationtools.otter.integration

import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Two sources sharing an `identifier` and differing only by `version` have to coexist.
 *
 * `:app-recorder`'s legacy migration depends on it: every migrated project reads as ULB, so both of
 * its sources carry `identifier: 'ulb'` and the recording mode rides on the dublin_core version
 * (`12-verse` / `12-chunk`), alongside the plain `ulb` `12` that `InitializeUlb` installs.
 *
 * The database allows it — `UNIQUE (language_fk, identifier, version, creator, derivedFrom_fk)` —
 * but three places above it identify a source by language and identifier alone, and each has to be
 * accounted for:
 *
 *  1. `ResourceContainerRepository.insertMetadataOrThrow` decides whether a second row may be
 *     inserted at all, so it compares the full identity. These tests cover that.
 *  2. `ExistingSourceImporter.findExistingResourceMetadata` would take a second version as an update
 *     of the first and rewrite it in place, so this route bypasses it via `NewSourceImporter` — what
 *     [IntegrationEnvironment.importAsNewSource] does.
 *  3. `RCImporter.isAlreadyImported` matches the same way; its only consumer is `InitializeUlb`.
 */
class SourceVersionCoexistenceTest {

    private var env: IntegrationEnvironment? = null

    @AfterTest
    fun tearDown() {
        env?.close()
        env = null
    }

    /** The bundled ULB imported through the normal chain, as `InitializeUlb` does. */
    private fun environmentWithUlb(): IntegrationEnvironment =
        IntegrationEnvironment.create().also { env = it }.import(ULB)

    /** A mode source: same identifier, [version] instead, trimmed to one book. */
    private fun IntegrationEnvironment.importMode(
        version: String,
        bridged: Boolean = false
    ): ImportResult = importAsNewSource(withVersion(ULB, version, setOf(BOOK), bridged))

    /** Jude's verse rows for the source at [version], as `start-end` strings in verse order. */
    private fun IntegrationEnvironment.judeUnits(version: String): List<String> {
        val metadata = db.resourceMetadataDao.fetchAll()
            .first { it.identifier == "ulb" && it.version == version }
        val chapter = db.collectionDao.fetchAll()
            .first { it.dublinCoreFk == metadata.id && it.slug == "${BOOK}_1" }
        val textType = db.contentTypeDao.fetchId(ContentType.TEXT)
        return db.contentDao.fetchByCollectionId(chapter.id)
            .filter { it.type_fk == textType && !it.bridged }
            .sortedBy { it.start }
            .map { if (it.start == it.end) "${it.start}" else "${it.start}-${it.end}" }
    }

    @Test
    fun `a second version of the same identifier imports alongside the first`() {
        val env = environmentWithUlb()

        assertEquals(ImportResult.SUCCESS, env.importMode("12-chunk"))

        assertEquals(
            listOf("ulb" to "12", "ulb" to "12-chunk"),
            env.sourceIdentities(),
            "the plain ULB and the chunk-mode ULB must both be present"
        )
    }

    @Test
    fun `all three ULB identities coexist`() {
        // What a migrated install holds: the bundled ULB plus one source per legacy mode.
        val env = environmentWithUlb()

        assertEquals(ImportResult.SUCCESS, env.importMode("12-chunk"))
        assertEquals(ImportResult.SUCCESS, env.importMode("12-verse"))

        assertEquals(
            listOf("ulb" to "12", "ulb" to "12-chunk", "ulb" to "12-verse"),
            env.sourceIdentities()
        )
    }

    @Test
    fun `the plain ULB is not touched by importing a mode source`() {
        // The failure this design most has to avoid is a mode source being treated as an update
        // of the bundled ULB, rewriting its rows and its version.
        val env = environmentWithUlb()
        val collectionsBefore = env.db.collectionDao.fetchAll().size

        env.importMode("12-chunk")

        val plain = env.db.resourceMetadataDao.fetchAll()
            .firstOrNull { it.identifier == "ulb" && it.version == "12" }
        assertNotNull(plain, "the bundled ULB row must survive under its own version")
        assertTrue(
            env.db.collectionDao.fetchAll().size > collectionsBefore,
            "the mode source must add collections, not replace the ULB's"
        )
        // The bundled ULB still covers the whole canon; a trimmed mode source overwriting it in
        // place would leave only the trimmed book.
        val plainBooks = env.db.collectionDao.fetchAll()
            .filter { it.dublinCoreFk == plain.id && it.slug in CANON_SPOT_CHECK }
        assertEquals(
            CANON_SPOT_CHECK.size,
            plainBooks.size,
            "the ULB's own books must be intact"
        )
    }

    @Test
    fun `re-importing the identical version is still rejected`() {
        // A genuine duplicate must still be refused, and cleanly: ALREADY_EXISTS rather than a
        // database unique-constraint violation.
        val env = environmentWithUlb()
        env.importMode("12-chunk")

        assertEquals(ImportResult.ALREADY_EXISTS, env.importMode("12-chunk"))

        assertEquals(listOf("ulb" to "12", "ulb" to "12-chunk"), env.sourceIdentities())
    }

    @Test
    fun `a mode source carries its own content rows`() {
        // Coexisting metadata is not enough, since a derived project's units come from these rows.
        val env = environmentWithUlb()
        env.importMode("12-chunk")

        val mode = env.db.resourceMetadataDao.fetchAll()
            .first { it.identifier == "ulb" && it.version == "12-chunk" }
        val chapters = env.db.collectionDao.fetchAll()
            .filter { it.dublinCoreFk == mode.id && it.slug == "${BOOK}_1" }
        assertEquals(1, chapters.size, "Jude has one chapter; got ${chapters.map { it.slug }}")

        val textType = env.db.contentTypeDao.fetchId(ContentType.TEXT)
        val verses = env.db.contentDao.fetchByCollectionId(chapters.single().id)
            .count { it.type_fk == textType }
        assertEquals(25, verses, "Jude has 25 verses")
    }

    @Test
    fun `a bridged mode source keeps its bridges, and the plain ULB keeps none`() {
        // `updateContent` decides which source the parsed text is overlaid onto. Matching by
        // language and identifier alone takes the first source row, putting the chunk-mode text on
        // the plain ULB: the mode source then keeps only its versification pre-allocation, one row
        // per verse with no bridges, so a chunk-mode project derives verse-by-verse while the plain
        // ULB gains bridged verses it should not have.
        val env = environmentWithUlb()

        assertEquals(ImportResult.SUCCESS, env.importMode("12-chunk", bridged = true))

        val chunkUnits = env.judeUnits("12-chunk")
        assertEquals(
            "1-2",
            chunkUnits.first(),
            "the chunk-mode source must carry the bridge; got ${chunkUnits.take(4)}"
        )
        assertEquals(24, chunkUnits.size, "25 verses with 1-2 merged leaves 24 units")

        val plainUnits = env.judeUnits("12")
        assertEquals(
            25,
            plainUnits.size,
            "the plain ULB must keep 25 separate verses; got ${plainUnits.take(4)}"
        )
        assertTrue(
            plainUnits.none { it.contains("-") },
            "the plain ULB must not have been bridged: ${plainUnits.filter { it.contains("-") }}"
        )
    }

    @Test
    fun `bridging one mode source does not reach the other`() {
        val env = environmentWithUlb()

        env.importMode("12-chunk", bridged = true)
        env.importMode("12-verse", bridged = false)

        assertEquals("1-2", env.judeUnits("12-chunk").first(), "chunk mode keeps its bridge")
        assertEquals(24, env.judeUnits("12-chunk").size)
        assertEquals(25, env.judeUnits("12-verse").size, "verse mode stays verse-by-verse")
        assertEquals(25, env.judeUnits("12").size, "the plain ULB stays verse-by-verse")
    }

    private companion object {
        const val ULB = "en_ulb.zip"

        /** One chapter, 25 verses — the smallest book, so the mode-source import stays cheap. */
        const val BOOK = "jud"

        /** Books the trimmed mode source does NOT contain, so their survival is meaningful. */
        val CANON_SPOT_CHECK = setOf("gen", "psa", "mat", "rev")
    }
}
