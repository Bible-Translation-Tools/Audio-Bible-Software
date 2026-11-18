package org.bibletranslationtools.otter.common.initialization

import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.reactivex.Observable
import kotlinx.coroutines.test.runTest
import org.bibletranslationtools.otter.common.api.io.IBundledContentSource
import org.bibletranslationtools.otter.common.api.persistence.IAppDirectories
import org.bibletranslationtools.otter.common.api.persistence.repositories.IVersificationRepository
import org.bibletranslationtools.otter.common.data.ProgressStatus
import org.bibletranslationtools.otter.common.domain.versification.StandardVersifications
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

private const val ULB_RESOURCE = "files/versification/ulb_versification.json"

class InitializeVersificationTest {

    /** Serves canned text per path; any other path fails like a missing resource. */
    private class FakeBundledContent(private val files: Map<String, String>) : IBundledContentSource {
        override suspend fun read(path: String): ByteArray =
            files[path]?.encodeToByteArray() ?: throw NoSuchElementException("no bundled resource $path")

        override fun readBlocking(path: String): ByteArray = error("startup must not block on bundled content")
    }

    private val dir: File = Files.createTempDirectory("versification").toFile()
    private val directories = mockk<IAppDirectories> { every { versificationDirectory } returns dir }
    private val repository = mockk<IVersificationRepository>(relaxUnitFun = true)

    private val allBundled = StandardVersifications.all
        .associate { StandardVersifications.resourcePath(it) to "{\"code\":\"$it\"}" } + (ULB_RESOURCE to "{\"code\":\"ulb\"}")
    private val title = ProgressStatus(titleKey = "initializingVersification")

    private fun initializer(bundled: Map<String, String>) =
        InitializeVersification(directories, repository, FakeBundledContent(bundled))

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun `copies every bundled versification and registers each file`() = runTest {
        val progress = mutableListOf<ProgressStatus>()

        initializer(allBundled).execSuspend { progress += it }

        assertEquals(listOf(title), progress)
        val slugs = listOf("ulb", "ufw") + StandardVersifications.all
        assertEquals(slugs.map { "$it.json" }.toSet(), dir.list()!!.toSet())
        assertEquals("{\"code\":\"ulb\"}", File(dir, "ufw.json").readText())
        slugs.forEach { coVerify(exactly = 1) { repository.insertVersificationSuspend(it, File(dir, "$it.json")) } }
    }

    @Test
    fun `a bundled file that cannot be read is skipped`() = runTest {
        val engOnly = allBundled.filterKeys { it == StandardVersifications.resourcePath(StandardVersifications.ENG) }

        initializer(engOnly).execSuspend {}

        assertEquals(setOf("eng.json"), dir.list()!!.toSet())
        coVerify(exactly = 1) { repository.insertVersificationSuspend(any(), any()) }
    }

    @Test
    fun `the Rx exec does the same work`() {
        val progress = Observable.create<ProgressStatus> { emitter ->
            initializer(allBundled).exec(emitter).blockingAwait()
            emitter.onComplete()
        }.toList().blockingGet()

        assertEquals(listOf(title), progress)
        coVerify(exactly = 5) { repository.insertVersificationSuspend(any(), any()) }
    }
}
