package org.bibletranslationtools.otter.common.initialization

import io.mockk.every
import io.mockk.mockk
import io.reactivex.Completable
import io.reactivex.ObservableEmitter
import org.bibletranslationtools.otter.common.api.persistence.ITempFileProvider
import org.bibletranslationtools.otter.common.api.persistence.config.AppStartupStep
import org.bibletranslationtools.otter.common.api.persistence.config.Initializable
import org.bibletranslationtools.otter.common.data.ProgressStatus
import org.bibletranslationtools.otter.common.persistence.database.dao.DaoProvider
import kotlin.test.Test
import kotlin.test.assertEquals

class InitializeAppStepsTest {

    private val ran = mutableListOf<String>()
    private var inBulkLoad = false

    private fun record(name: String) = Completable.fromAction { ran += if (inBulkLoad) "$name (bulk)" else name }

    private inline fun <reified T : Initializable> shared(name: String): T =
        mockk { every { exec(any()) } returns record(name) }

    private fun appStep(name: String) = object : AppStartupStep {
        override val name = name
        override val version = 1
        override fun exec(progressEmitter: ObservableEmitter<ProgressStatus>) = record(name)
    }

    private fun initializeApp(appSteps: List<AppStartupStep>) = InitializeApp(
        shared<InitializeVersification>("versification"),
        shared<InitializeSources>("sources"),
        shared<InitializeLanguages>("languages"),
        shared<InitializeUlb>("ulb"),
        shared<InitializeTakeRepository>("takes"),
        shared<InitializeProjects>("projects"),
        shared<InitializeTranslations>("translations"),
        shared<BackfillEditionFingerprints>("fingerprints"),
        shared<ReconcileEditionLabels>("labels"),
        shared<RefreshBundledSources>("bundled"),
        shared<AuditSourceStructure>("audit"),
        mockk<ITempFileProvider>(relaxed = true),
        mockk<DaoProvider> {
            every { withBulkLoad<Any?>(any()) } answers {
                inBulkLoad = true
                try {
                    firstArg<() -> Any?>().invoke()
                } finally {
                    inBulkLoad = false
                }
            }
        },
        appSteps
    )

    @Test
    fun `the app's own steps run after the shared ones, outside the bulk load`() {
        initializeApp(listOf(appStep("migration"))).initApp().blockingSubscribe()

        assertEquals("migration", ran.last())
        assertEquals(12, ran.size)
        assertEquals(11, ran.count { it.endsWith("(bulk)") }, "$ran")
    }

    @Test
    fun `with no steps of its own, an app runs just the shared ones`() {
        initializeApp(emptyList()).initApp().blockingSubscribe()

        assertEquals(11, ran.size)
    }
}
