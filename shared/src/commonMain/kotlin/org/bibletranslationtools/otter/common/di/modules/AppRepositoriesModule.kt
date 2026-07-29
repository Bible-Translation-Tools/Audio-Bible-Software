/**
 * Copyright (C) 2020-2024 Wycliffe Associates
 *
 * This file is part of Orature.
 *
 * Orature is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Orature is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Orature.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.bibletranslationtools.otter.common.di.modules

import dagger.Binds
import dagger.Module
import org.bibletranslationtools.otter.common.api.persistence.ILanguageDataSource
import org.bibletranslationtools.otter.common.api.persistence.repositories.ICollectionRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IContentRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IInstalledEntityRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.ILanguageRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceContainerRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceMetadataRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.ITakeRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IVersificationRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookDescriptorRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.bibletranslationtools.otter.common.persistence.repositories.WorkbookRepository
import org.bibletranslationtools.otter.common.domain.languages.LanguageDataSource
import org.bibletranslationtools.otter.common.persistence.repositories.LanguageRepository
import org.bibletranslationtools.otter.common.persistence.repositories.*
import javax.inject.Singleton

@Module
abstract class AppRepositoriesModule {
    @Binds
    @Singleton
    abstract fun providesLanguageRepo(
        repository: LanguageRepository
    ): ILanguageRepository

    @Binds
    @Singleton
    abstract fun providesCollectionRepo(
        repository: CollectionRepository
    ): ICollectionRepository

    @Binds
    @Singleton
    abstract fun providesContentRepository(
        repository: ContentRepository
    ): IContentRepository

    @Binds
    @Singleton
    abstract fun providesResourceRepository(
        repository: ResourceRepository
    ): IResourceRepository

    @Binds
    @Singleton
    abstract fun providesResourceContainerRepository(
        repository: ResourceContainerRepository
    ): IResourceContainerRepository

    @Binds
    @Singleton
    abstract fun providesResourceMetadataRepository(
        repository: ResourceMetadataRepository
    ): IResourceMetadataRepository

    @Binds
    @Singleton
    abstract fun providesTakeRepository(
        repository: TakeRepository
    ): ITakeRepository

    @Binds
    @Singleton
    abstract fun providesWorkbookRepository(
        repository: WorkbookRepository
    ): IWorkbookRepository

    @Binds
    @Singleton
    abstract fun providesWorkbookDescriptorRepository(
        repository: WorkbookDescriptorRepository
    ): IWorkbookDescriptorRepository

    @Binds
    @Singleton
    abstract fun providesInstalledEntityRepository(
        repository: InstalledEntityRepository
    ): IInstalledEntityRepository

    @Binds
    @Singleton
    abstract fun providesVersificationRepository(
        repository: VersificationRepository
    ): IVersificationRepository

    @Binds
    @Singleton
    abstract fun providesLanguageDataSource(
        dataSource: LanguageDataSource
    ): ILanguageDataSource
}
