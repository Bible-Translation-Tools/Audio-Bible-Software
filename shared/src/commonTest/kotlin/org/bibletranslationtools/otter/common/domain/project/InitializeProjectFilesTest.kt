package org.bibletranslationtools.otter.common.domain.project

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.domain.resourcecontainer.project.ProjectFilesAccessor
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * These five writes used to sit in a `runCatching` inside Orature's `OratureWorkbookDataStore`,
 * with the failure branch printing to stderr — so nothing about the order, the stop-on-first-failure
 * behaviour, or which failures are survivable was pinned by anything.
 */
class InitializeProjectFilesTest {

    private val accessor: ProjectFilesAccessor = mockk(relaxed = true)
    private val workbook: Workbook = mockk {
        every { projectFilesAccessor } returns accessor
    }
    private val useCase = InitializeProjectFiles()
    private val mode = ProjectMode.TRANSLATION

    @Test
    fun `writes every project file and reports success`() = runTest {
        val result = useCase.execute(workbook, mode)

        assertEquals(InitializeProjectFiles.Result.Success, result)
        verify(exactly = 1) { accessor.initializeResourceContainerInDir(overwrite = false) }
        verify(exactly = 1) { accessor.copySourceFiles() }
        verify(exactly = 1) { accessor.createSelectedTakesFile() }
        verify(exactly = 1) { accessor.createChunksFile() }
        verify(exactly = 1) { accessor.setProjectMode(mode) }
    }

    /**
     * The resource container must exist before anything writes into its directory, and the mode
     * file is written last so a half-scaffolded project is not marked as ready.
     */
    @Test
    fun `writes the resource container first and the project mode last`() = runTest {
        useCase.execute(workbook, mode)

        verifyOrder {
            accessor.initializeResourceContainerInDir(overwrite = false)
            accessor.copySourceFiles()
            accessor.createSelectedTakesFile()
            accessor.createChunksFile()
            accessor.setProjectMode(mode)
        }
    }

    /** `overwrite = false` is what makes reopening a project non-destructive. */
    @Test
    fun `never overwrites an existing resource container`() = runTest {
        useCase.execute(workbook, mode)

        verify(exactly = 0) { accessor.initializeResourceContainerInDir(overwrite = true) }
    }

    @Test
    fun `names the step that failed`() = runTest {
        val boom = IOException("disk full")
        every { accessor.createChunksFile() } throws boom

        val result = useCase.execute(workbook, mode)

        val failed = result as InitializeProjectFiles.Result.Failed
        assertEquals(InitializeProjectFiles.Step.CHUNKS, failed.step)
        assertSame(boom, failed.cause)
    }

    /**
     * Stopping matters: the later steps write into the resource container directory, so continuing
     * past a failed one would bury the interesting failure under consequential ones.
     */
    @Test
    fun `stops at the first failure`() = runTest {
        every { accessor.copySourceFiles() } throws IOException("no source")

        val result = useCase.execute(workbook, mode)

        assertEquals(
            InitializeProjectFiles.Step.SOURCE_FILES,
            (result as InitializeProjectFiles.Result.Failed).step
        )
        verify(exactly = 0) { accessor.createSelectedTakesFile() }
        verify(exactly = 0) { accessor.createChunksFile() }
        verify(exactly = 0) { accessor.setProjectMode(any()) }
    }

    /**
     * A missing manifest.yaml is the failure that used to surface much later and somewhere else,
     * as a ResourceContainer.load blowing up inside chunking. It is the one that must stop the open.
     */
    @Test
    fun `a failed resource container leaves the project unusable`() = runTest {
        every { accessor.initializeResourceContainerInDir(overwrite = false) } throws
            IOException("cannot write manifest")

        val result = useCase.execute(workbook, mode)

        val failed = result as InitializeProjectFiles.Result.Failed
        assertEquals(InitializeProjectFiles.Step.RESOURCE_CONTAINER, failed.step)
        assertFalse(failed.projectUsable, "nothing that loads the project RC can work without it")
    }

    /** The other four degrade a working project rather than preventing one. */
    @Test
    fun `the optional steps leave the project usable`() = runTest {
        val cases: List<Pair<InitializeProjectFiles.Step, ProjectFilesAccessor.() -> Unit>> = listOf(
            InitializeProjectFiles.Step.SOURCE_FILES to { every { copySourceFiles() } throws IOException() },
            InitializeProjectFiles.Step.SELECTED_TAKES to { every { createSelectedTakesFile() } throws IOException() },
            InitializeProjectFiles.Step.CHUNKS to { every { createChunksFile() } throws IOException() },
            InitializeProjectFiles.Step.PROJECT_MODE to { every { setProjectMode(any()) } throws IOException() }
        )

        for ((step, breakThatStep) in cases) {
            // A fresh mock per case, so each starts from every-step-succeeding.
            val freshAccessor: ProjectFilesAccessor = mockk(relaxed = true)
            freshAccessor.breakThatStep()
            val freshWorkbook: Workbook = mockk {
                every { projectFilesAccessor } returns freshAccessor
            }

            val result = InitializeProjectFiles().execute(freshWorkbook, mode)

            val failed = result as InitializeProjectFiles.Result.Failed
            assertEquals(step, failed.step)
            assertTrue(failed.projectUsable, "$step should not prevent opening the project")
        }
    }

    /**
     * Cancellation is the caller's screen going away, not a scaffolding failure. Swallowing it into
     * a [InitializeProjectFiles.Result.Failed] would break cancellation for whoever is awaiting this.
     */
    @Test
    fun `propagates cancellation instead of reporting it as a failure`() = runTest {
        every { accessor.createSelectedTakesFile() } throws CancellationException("screen closed")

        assertFailsWith<CancellationException> { useCase.execute(workbook, mode) }
    }
}
