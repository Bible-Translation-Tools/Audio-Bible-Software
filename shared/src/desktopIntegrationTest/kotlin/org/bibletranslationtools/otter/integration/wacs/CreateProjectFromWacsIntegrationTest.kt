package org.bibletranslationtools.otter.integration.wacs

import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.domain.wacs.auth.WacsCredential
import org.bibletranslationtools.otter.common.domain.wacs.layout.WacsRepoLayout
import org.bibletranslationtools.otter.common.domain.wacs.layout.WacsTakeProvenance
import org.bibletranslationtools.otter.common.domain.wacs.lfs.Sha256
import org.bibletranslationtools.otter.common.domain.wacs.usecase.CreateProjectFromWacs
import org.bibletranslationtools.otter.integration.IntegrationEnvironment
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Opt-in, end-to-end M5(d) test: real SQLite (via [IntegrationEnvironment]) + the real WACS
 * prototype (`AudioTranslation/audio-en-ulb-mrk`), exercising [CreateProjectFromWacs] the way
 * nothing else in the tree does. Every other WACS integration test so far starts from an
 * *existing* matching project — `RestoreChapterFromWacsIntegrationTest` explicitly imports
 * `en_ulb.zip` before creating its test project. This one deliberately does NOT: no imported
 * source, no local project at all — the actual device-loss case M5(d) targets. [CreateProjectFromWacs]
 * must sideload English ULB itself, derive language/edition/book from the repo alone, create the
 * project via the same [org.bibletranslationtools.otter.common.domain.collections.CreateProject]
 * machinery a human wizard would use, and restore chapter 1 into it as a take.
 *
 * Building this test is what surfaced a real, pre-existing bug: [org.bibletranslationtools.otter.common.domain.project.ImportProjectUseCase.sideloadSource]
 * read the bundled GL zip via a plain JVM classpath lookup, but Compose Multiplatform resources are
 * NOT on the JVM classpath (see [org.bibletranslationtools.shared.content.ComposeBundledContentSource]'s
 * KDoc) — so it threw a `NullPointerException` the moment anything actually called it, in production
 * as much as here. Fixed alongside this test (see that class's KDoc) by routing through the
 * `IBundledContentSource`/`GlSourceCatalog` ports it already took as constructor parameters but
 * never used.
 *
 * Skipped unless `WACS_USER`/`WACS_PASS` are set (same self-skip convention as every other WACS
 * test — keeps `:shared:integrationTest` runnable with no prototype up). `WACS_BASE_URL` overrides
 * the default `http://localhost:3000` if needed.
 *
 * Run: `WACS_USER=user1 WACS_PASS=user1234 ./gradlew :shared:integrationTest --tests "*CreateProjectFromWacsIntegrationTest*"`
 *
 * Pure pull/restore — no writes to the server, so nothing to clean up there.
 * [IntegrationEnvironment.close] tears down the temp DB/project tree (including the sideloaded
 * source RC) and [CloneWacsRepo]'s repo cache, which lives under the same environment's cache dir.
 */
class CreateProjectFromWacsIntegrationTest {

    private var env: IntegrationEnvironment? = null

    @AfterTest
    fun tearDown() {
        env?.close()
        env = null
    }

    @Test
    fun `creating a project from a WACS repo with no matching local project sideloads the source, creates the project, and restores chapter 1`() {
        val user = System.getenv("WACS_USER")
        val pass = System.getenv("WACS_PASS")
        if (user.isNullOrBlank() || pass.isNullOrBlank()) return // self-skip: no prototype creds, no test
        val baseUrl = (System.getenv("WACS_BASE_URL") ?: "http://localhost:3000").trimEnd('/')

        // Deliberately NOT importing en_ulb.zip - this is the device-loss case: no local project,
        // no imported source, nothing but a fresh database (see this class's KDoc).
        val environment = IntegrationEnvironment.create().also { env = it }
        environment.wacsSession.set(WacsCredential.Basic(user, pass), baseUrl)

        val beforeDescriptor = runBlocking {
            environment.workbookDescriptorRepository.getAllSuspend(computeSourceAudio = false)
                .firstOrNull { WacsRepoLayout.repoNameOrNull(it) == "audio-en-ulb-mrk" }
        }
        assertNull(beforeDescriptor, "test must start with no local project matching the repo")

        val opened = runBlocking { environment.cloneWacsRepo.open("audio-en-ulb-mrk") }
        val ingredient = opened.scope.books["MRK"]?.find { it.chapterNumber == 1 }
            ?: error("expected chapter 1 in the prototype's MRK scope, got: ${opened.scope.books}")
        val expectedOid = runBlocking {
            environment.wacsGitClient.listLfsPointers(opened.workDir)
                .first { it.path == ingredient.audioPath }.pointer.oid
        }

        // ── The call under test: create the project (sideloading English ULB) + restore ch.1 ──
        val result = runBlocking {
            environment.createProjectFromWacs.create(opened.repo, opened.workDir, opened.scope)
        }

        assertEquals(
            "audio-en-ulb-mrk",
            WacsRepoLayout.repoNameOrNull(result.descriptor),
            "the created project must be the one this repo restores into — repo-name derivation must round-trip"
        )
        assertEquals("mrk", result.descriptor.targetCollection.slug, "created project must be for Mark")
        assertEquals("en", result.descriptor.targetLanguage.slug, "created project's target language must be English")
        assertEquals("en", result.descriptor.sourceLanguage.slug, "a NARRATION project's source language equals its target")

        val outcome1 = result.chapterOutcomes[1]
        val restored1 = assertIs<CreateProjectFromWacs.ChapterOutcome.Restored>(outcome1)
        assertTrue(
            restored1.selected,
            "a freshly created project's chapter 1 has no prior takes - the restored take must be selected"
        )
        assertTrue(restored1.take.file.exists() && restored1.take.file.length() > 0)

        // The same dedup provenance RestoreChapterFromWacs writes - see that class's KDoc.
        assertEquals(expectedOid, WacsTakeProvenance.read(restored1.take.file))
        assertEquals(expectedOid, Sha256.hex(restored1.take.file), "materialized audio must hash to the pointer's oid")

        val chapter1Collection = environment.childrenOf(result.descriptor.targetCollection).first { it.sort == 1 }
        val chapter1MetaContent = environment.db.contentDao
            .fetchByCollectionIdAndType(chapter1Collection.id, ContentType.META)
            .first()
        val takeEntities = environment.db.takeDao.fetchByContentId(chapter1MetaContent.id)
        assertEquals(1, takeEntities.size, "exactly one take row after project creation + restore")
        assertNull(takeEntities.single().checksum, "checksum must stay null - it is checking-status MD5, not take provenance")
    }
}
