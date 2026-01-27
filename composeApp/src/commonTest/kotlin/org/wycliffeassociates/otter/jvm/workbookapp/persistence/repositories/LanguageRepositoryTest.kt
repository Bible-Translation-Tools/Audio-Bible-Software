package org.wycliffeassociates.otter.jvm.workbookapp.persistence.repositories

import io.mockk.*
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.test.runTest
import org.bibletranslationtools.otter.common.api.persistence.AppDatabase
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.persistence.database.daos.LanguageDao
import org.bibletranslationtools.otter.common.persistence.database.daos.TranslationDao
import org.wycliffeassociates.otter.jvm.workbookapp.persistence.repositories.mapping.LanguageMapper
import org.wycliffeassociates.otter.jvm.workbookapp.persistence.repositories.mapping.TranslationMapper
import org.wycliffeassociates.otter.jvm.workbookapp.persistence.entities.LanguageEntity
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LanguageRepositoryTest {

    private val db = mockk<AppDatabase>()
    private val languageDao = mockk<LanguageDao>()
    private val translationDao = mockk<TranslationDao>()
    private val mapper = mockk<LanguageMapper>()
    private val translationMapper = mockk<TranslationMapper>()

    private lateinit var repository: LanguageRepository

    @BeforeTest
    fun setup() {
        mockkStatic(Schedulers::class)
        every { Schedulers.io() } returns Schedulers.trampoline()

        every { db.languageDao } returns languageDao
        every { db.translationDao } returns translationDao

        repository = LanguageRepository(db, mapper, translationMapper)
    }

    @Test
    fun testInsertSuspend() = runTest {
        val language = mockk<Language>()
        val entity = mockk<LanguageEntity>()
        val expectedId = 1
        
        every { mapper.mapToEntity(language) } returns entity
        every { languageDao.insert(entity) } returns expectedId
        
        val result = repository.insertSuspend(language)
        assertEquals(expectedId, result)
        verify { languageDao.insert(entity) }
    }

    @Test
    fun testGetAllSuspend() = runTest {
        val entity = mockk<LanguageEntity>()
        val language = mockk<Language>()
        
        every { languageDao.fetchAll() } returns listOf(entity)
        every { mapper.mapFromEntity(entity) } returns language
        
        val result = repository.getAllSuspend()
        assertEquals(1, result.size)
        verify { languageDao.fetchAll() }
    }

    @Test
    fun testGetBySlugSuspend() = runTest {
        val slug = "en"
        val entity = mockk<LanguageEntity>()
        val language = mockk<Language>()
        
        every { languageDao.fetchBySlug(slug) } returns entity
        every { mapper.mapFromEntity(entity) } returns language
        
        val result = repository.getBySlugSuspend(slug)
        assertEquals(language, result)
        verify { languageDao.fetchBySlug(slug) }
    }
}
