package org.wycliffeassociates.otter.jvm.workbookapp.persistence.repositories

import io.mockk.*
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.test.runTest
import org.bibletranslationtools.otter.common.api.persistence.IAppDatabase
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.persistence.database.daos.VersificationDao
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class VersificationRepositoryTest {

    private val db = mockk<IAppDatabase>()
    private val directoryProvider = mockk<IDirectoryProvider>()
    private val versificationDao = mockk<VersificationDao>()

    private lateinit var repository: VersificationRepository

    @BeforeTest
    fun setup() {
        mockkStatic(Schedulers::class)
        every { Schedulers.io() } returns Schedulers.trampoline()

        every { db.versificationDao } returns versificationDao
        
        repository = VersificationRepository(db, directoryProvider)
    }

    @Test
    fun testInsertVersificationSuspend() = runTest {
        val slug = "eng"
        val path = File("test.vrs")
        
        every { versificationDao.upsert(slug, any<String>()) } just Runs
        
        repository.insertVersificationSuspend(slug, path)
        verify { versificationDao.upsert(slug, any<String>()) }
    }
}
