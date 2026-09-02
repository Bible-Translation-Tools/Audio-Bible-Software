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
package org.bibletranslationtools.otter.common.persistence.database.dao

/**
 * The clean, jOOQ-free counterpart of [org.bibletranslationtools.otter.common.persistence.database.IAppDatabase]:
 * the set of DAOs, exposed as the interfaces in this package. The SQLDelight backend implements this
 * directly; during coexistence a test adapter presents the jOOQ backend through the same interfaces,
 * so the shared characterization suite can run — and prove equivalence — against both.
 *
 * Repositories migrate onto this in Phase 4; it replaces IAppDatabase's DAO surface in Phase 6.
 */
interface DaoProvider {
    val languageDao: LanguageDao
    val resourceMetadataDao: ResourceMetadataDao
    val collectionDao: CollectionDao
    val contentTypeDao: ContentTypeDao
    val contentDao: ContentDao
    val resourceLinkDao: ResourceLinkDao
    val subtreeHasResourceDao: SubtreeHasResourceDao
    val takeDao: TakeDao
    val markerDao: MarkerDao
    val installedEntityDao: InstalledEntityDao
    val translationDao: TranslationDao
    val versificationDao: VersificationDao
    val workbookTypeDao: WorkbookTypeDao
    val workbookDescriptorDao: WorkbookDescriptorDao
    val checkingStatusDao: CheckingStatusDao

    fun transaction(block: () -> Unit)
    fun <T> transactionResult(block: () -> T): T
}
