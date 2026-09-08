package org.bibletranslationtools.otter.integration.wacs

import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.domain.wacs.auth.WacsCredential
import org.bibletranslationtools.otter.common.domain.wacs.layout.WacsRepoLayout
import org.bibletranslationtools.otter.common.domain.wacs.layout.WacsTakeProvenance
import org.bibletranslationtools.otter.common.domain.wacs.lfs.Sha256
import org.bibletranslationtools.otter.common.domain.wacs.usecase.RestoreChapterFromWacs
import org.bibletranslationtools.otter.integration.IntegrationEnvironment
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Opt-in, end-to-end M3.1 test: real SQLite (via [IntegrationEnvironment]) + the real WACS prototype
 * (`AudioTranslation/audio-en-ulb-mrk`), exercising [RestoreChapterFromWacs] the way nothing else in
 * the tree does — every other WACS "DesktopTest" under commonTest's wacs/usecase package stops at
 * the git/LFS layer and never touches a project database; every other database test in this
 * `desktopIntegrationTest` tier never touches the network. This is the join.
 *
 * Skipped unless `WACS_USER`/`WACS_PASS` are set (same self-skip convention as every other WACS
 * test — keeps `:shared:integrationTest` runnable with no prototype up). `WACS_BASE_URL` overrides
 * the default `http://localhost:3000` if needed.
 *
 * Run: `WACS_USER=user1 WACS_PASS=user1234 ./gradlew :shared:integrationTest --tests "*RestoreChapterFromWacsIntegrationTest*"`
 *
 * Pure pull/restore — no writes to the server, so nothing to clean up there (unlike the publish
 * test, which forks + opens a PR). [IntegrationEnvironment.close] tears down the temp DB/project
 * tree; [CloneWacsRepo]'s repo cache lives under the same environment's cache directory, so it goes
 * with it.
 *
 * ### What this deliberately does NOT assert, and why
 * The M3.1 design doc's plan was to key dedup on `take_entity.checksum == the pointer oid`.
 * Investigating the take model (see [RestoreChapterFromWacs]'s KDoc) found `checksum` is the take's
 * *checking-status* MD5, unrelated to and incompatible with the LFS sha256 oid — writing the oid
 * there would misreport a never-checked take as carrying saved checking state. So this test asserts
 * the real design instead: `checksum` stays null on a freshly restored take, and the oid lives in
 * [WacsTakeProvenance]'s sidecar file, which is what dedup actually reads.
 */
class RestoreChapterFromWacsIntegrationTest {

    private var env: IntegrationEnvironment? = null

    @AfterTest
    fun tearDown() {
        env?.close()
        env = null
    }

    @Test
    fun `restoring a chapter from WACS inserts a take as unselected or selected correctly, and re-restoring is a no-op`() {
        val user = System.getenv("WACS_USER")
        val pass = System.getenv("WACS_PASS")
        if (user.isNullOrBlank() || pass.isNullOrBlank()) return // self-skip: no prototype creds, no test
        val baseUrl = (System.getenv("WACS_BASE_URL") ?: "http://localhost:3000").trimEnd('/')

        val environment = IntegrationEnvironment.create().also { env = it }.import("en_ulb.zip")
        environment.wacsSession.set(WacsCredential.Basic(user, pass), baseUrl)

        // A narrated-English Mark project — matches the prototype's seeded
        // AudioTranslation/audio-en-ulb-mrk exactly (audio-<targetLang>-<edition>-<book>).
        val target = environment.createProject(
            sourceProject = environment.sourceBook("mrk"),
            targetLanguage = environment.language("en"),
            mode = ProjectMode.NARRATION,
            deriveProjectFromVerses = true,
        )

        val descriptor = runBlocking {
            environment.workbookDescriptorRepository.getAllSuspend(computeSourceAudio = false)
                .first { it.targetCollection.id == target.id }
        }
        assertEquals(
            "audio-en-ulb-mrk",
            WacsRepoLayout.repoNameOrNull(descriptor),
            "repo-name derivation must match the prototype's seeded repo for this test to mean anything"
        )

        val chapter1Collection = environment.childrenOf(target).first { it.sort == 1 }
        val chapter1MetaContent = environment.db.contentDao
            .fetchByCollectionIdAndType(chapter1Collection.id, ContentType.META)
            .first()

        fun takesForChapter1() = environment.db.takeDao.fetchByContentId(chapter1MetaContent.id)

        assertTrue(takesForChapter1().isEmpty(), "test project must start with no takes on chapter 1")

        val opened = runBlocking { environment.cloneWacsRepo.open("audio-en-ulb-mrk") }
        val ingredient = opened.scope.books["MRK"]?.find { it.chapterNumber == 1 }
            ?: error("expected chapter 1 in the prototype's MRK scope, got: ${opened.scope.books}")
        val expectedOid = runBlocking {
            environment.wacsGitClient.listLfsPointers(opened.workDir)
                .first { it.path == ingredient.audioPath }.pointer.oid
        }

        // ── 1. First restore: clean chapter (no existing takes) -> new take, and IT becomes selected ──
        val outcome1 = runBlocking {
            environment.restoreChapterFromWacs.restore(descriptor, opened.repo, opened.workDir, ingredient)
        }
        val restored1 = assertIs<RestoreChapterFromWacs.Outcome.Restored>(outcome1)
        assertTrue(restored1.selected, "a clean restore (no prior takes) must select the new take")
        assertTrue(restored1.take.file.exists() && restored1.take.file.length() > 0)

        // The dedup key: oid lives in the sidecar, not take_entity.checksum (see class KDoc above).
        assertEquals(expectedOid, WacsTakeProvenance.read(restored1.take.file))
        assertEquals(expectedOid, Sha256.hex(restored1.take.file), "materialized audio must hash to the pointer's oid")

        val entitiesAfterFirst = takesForChapter1()
        assertEquals(1, entitiesAfterFirst.size, "exactly one take row after the first restore")
        val entity1 = entitiesAfterFirst.single()
        assertEquals(restored1.take.file.name, entity1.filename)
        assertNull(entity1.checksum, "checksum must stay null - it is checking-status MD5, not take provenance")

        // ── 2. Re-restore: oid already present -> no-op, no second download, no second take ────────
        val outcome2 = runBlocking {
            environment.restoreChapterFromWacs.restore(descriptor, opened.repo, opened.workDir, ingredient)
        }
        assertEquals(RestoreChapterFromWacs.Outcome.AlreadyPresent, outcome2, "re-restoring the same oid must be a no-op")
        assertEquals(1, takesForChapter1().size, "no second take row after a no-op re-restore")
    }
}
