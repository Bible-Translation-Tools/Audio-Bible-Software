package org.bibletranslationtools.otter.integration

import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.persistence.entities.ContentEntity
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deriving a target project from an imported source — the step between "a source exists" and "a
 * translator can record into it".
 *
 * Not a port. The JavaFX app's `integrationtest.projects.TestProjectCreate` exists but both of its
 * tests are commented out, so `deriveProject` has never had end-to-end coverage in either codebase.
 * It is worth having because this is where a target project's structure comes from, and structure is
 * what today's take-selection bug hinged on: a take attaches to a *derived* content row, which has to
 * exist and be linked back to its source before anything can be recorded into it.
 *
 * Jude throughout: one chapter, 25 verses, so the expected numbers can be stated literally rather than
 * derived from the thing under test.
 *
 * ### Two paths, and they are not interchangeable
 *
 * `CreateProject.create` takes `mode` and `deriveProjectFromVerses` independently. Only
 * `createAllBooks` couples them (`isVerseByVerse = projectMode != TRANSLATION`). So a caller asking
 * for NARRATION without asking for verses gets a chapter and no verse rows — which is what the first
 * draft of this test did, and two of its assertions then passed against an empty set. Both paths are
 * pinned below, and every assertion over a verse list also pins the list's size.
 */
class ProjectCreateTest {

    private var env: IntegrationEnvironment? = null

    @AfterTest
    fun tearDown() {
        env?.close()
        env = null
    }

    /**
     * A ULB import plus a Jude project. Defaults mirror the recorder's own call
     * ([ProjectMode.NARRATION] with verses derived — see `ProjectCreationViewModel`).
     */
    private fun judeProject(
        targetSlug: String = "hbo",
        mode: ProjectMode? = ProjectMode.NARRATION,
        deriveProjectFromVerses: Boolean = true
    ): Pair<IntegrationEnvironment, Collection> {
        val environment = IntegrationEnvironment.create().also { env = it }.import("en_ulb.zip")
        val target = environment.createProject(
            sourceProject = environment.sourceBook("jud"),
            targetLanguage = environment.language(targetSlug),
            mode = mode,
            deriveProjectFromVerses = deriveProjectFromVerses
        )
        return environment to target
    }

    private fun IntegrationEnvironment.versesOf(chapter: Collection): Map<ContentEntity, List<ContentEntity>> {
        val textType = db.contentTypeDao.fetchId(ContentType.TEXT)
        return contentWithSources(chapter).filterKeys { it.type_fk == textType }
    }

    // ── the target book ──────────────────────────────────────────────────────────────────

    @Test
    fun `creating a project derives a target book in the target language`() {
        val (environment, target) = judeProject(targetSlug = "hbo")

        assertEquals("jud", target.slug, "the derived book keeps the source's slug")
        assertEquals("hbo", target.resourceContainer?.language?.slug, "derived into the target language")
        assertEquals(
            listOf("jud"),
            environment.derivedProjects().map { it.slug },
            "exactly one derived project, for the book that was created"
        )
    }

    /** The chapter has to exist in the target, or there is nowhere to record. */
    @Test
    fun `the derived book has the source's chapters`() {
        val (environment, target) = judeProject()

        val chapters = environment.childrenOf(target)

        assertEquals(1, chapters.size, "Jude is one chapter")
        assertEquals(1, chapters.single().sort, "same sort as the source, so navigation lines up")
    }

    /**
     * A workbook descriptor is what the home screen lists and what every screen is navigated to by
     * id, so creating a project has to produce one.
     */
    @Test
    fun `creating a project registers a workbook descriptor`() {
        val (environment, _) = judeProject()

        assertEquals(1, environment.db.workbookDescriptorDao.fetchAll().size)
    }

    // ── verse-by-verse: the recorder's path ──────────────────────────────────────────────

    @Test
    fun `a verse-by-verse project derives a row for every source verse`() {
        val (environment, target) = judeProject()
        val chapter = environment.childrenOf(target).single()

        assertEquals(25, environment.versesOf(chapter).size, "Jude has 25 verses")
    }

    /**
     * Each derived verse must link back to the source verse it translates. Chapter compilation and the
     * source-text panels walk these links; a derived row with no source is a verse the app cannot show
     * anything alongside.
     */
    @Test
    fun `every derived verse links back to its source verse`() {
        val (environment, target) = judeProject()
        val chapter = environment.childrenOf(target).single()

        val verses = environment.versesOf(chapter)

        assertEquals(25, verses.size, "guard: an empty verse list would satisfy the check below")
        val unlinked = verses.filterValues { it.isEmpty() }
        assertTrue(
            unlinked.isEmpty(),
            "${unlinked.size} of ${verses.size} derived verses have no source link " +
                "(starts: ${unlinked.keys.map { it.start }.sorted().take(8)})"
        )
    }

    /**
     * The derived rows must start empty. A target verse arriving with the source's text would read as
     * already translated, and to the recorder as already recorded.
     */
    @Test
    fun `derived verses start with no text of their own`() {
        val (environment, target) = judeProject()
        val chapter = environment.childrenOf(target).single()

        val verses = environment.versesOf(chapter)

        assertEquals(25, verses.size, "guard: an empty verse list would satisfy the check below")
        val withText = verses.keys.filter { it.text != null }
        assertTrue(withText.isEmpty(), "${withText.size} derived verses already carry text")
    }

    // ── translation mode: verses are chunking's job ──────────────────────────────────────

    /**
     * The contrast, and the thing that caught this test out. A translation project derives the chapter
     * and stops: its verse structure comes later, from chunking, because a translator may not chunk
     * one-verse-per-unit. Pinned so the difference is documented rather than rediscovered.
     */
    @Test
    fun `a translation project derives no verse rows`() {
        val (environment, target) = judeProject(
            targetSlug = "es",
            mode = ProjectMode.TRANSLATION,
            deriveProjectFromVerses = false
        )
        val chapter = environment.childrenOf(target).single()

        assertEquals(
            0,
            environment.versesOf(chapter).size,
            "translation projects get their units from chunking, not from the source's verses"
        )
    }
}
