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
package org.bibletranslationtools.otter.common.persistence.characterization

/**
 * The SQLDelight half of the differential: each class reuses the identical `<Dao>Characterization`
 * assertions the jOOQ tests run, bound to [SqlDelightBackend]. When every one of these passes
 * alongside its `Jooq<Dao>CharacterizationTest` twin, the two backends are proven equivalent for
 * everything the suite pins.
 */
class SqlDelightLanguageDaoCharacterizationTest : LanguageDaoCharacterization() {
    override val backend = SqlDelightBackend
}

class SqlDelightContentTypeDaoCharacterizationTest : ContentTypeDaoCharacterization() {
    override val backend = SqlDelightBackend
}

class SqlDelightCheckingStatusDaoCharacterizationTest : CheckingStatusDaoCharacterization() {
    override val backend = SqlDelightBackend
}

class SqlDelightWorkbookTypeDaoCharacterizationTest : WorkbookTypeDaoCharacterization() {
    override val backend = SqlDelightBackend
}

class SqlDelightInstalledEntityDaoCharacterizationTest : InstalledEntityDaoCharacterization() {
    override val backend = SqlDelightBackend
}

class SqlDelightVersificationDaoCharacterizationTest : VersificationDaoCharacterization() {
    override val backend = SqlDelightBackend
}

class SqlDelightContentDaoCharacterizationTest : ContentDaoCharacterization() {
    override val backend = SqlDelightBackend
}

class SqlDelightMarkerDaoCharacterizationTest : MarkerDaoCharacterization() {
    override val backend = SqlDelightBackend
}

class SqlDelightTakeDaoCharacterizationTest : TakeDaoCharacterization() {
    override val backend = SqlDelightBackend
}

class SqlDelightCollectionDaoCharacterizationTest : CollectionDaoCharacterization() {
    override val backend = SqlDelightBackend
}

class SqlDelightWorkbookDescriptorDaoCharacterizationTest : WorkbookDescriptorDaoCharacterization() {
    override val backend = SqlDelightBackend
}

class SqlDelightTranslationDaoCharacterizationTest : TranslationDaoCharacterization() {
    override val backend = SqlDelightBackend
}

class SqlDelightResourceLinkDaoCharacterizationTest : ResourceLinkDaoCharacterization() {
    override val backend = SqlDelightBackend
}

class SqlDelightSubtreeHasResourceDaoCharacterizationTest : SubtreeHasResourceDaoCharacterization() {
    override val backend = SqlDelightBackend
}

class SqlDelightResourceMetadataDaoCharacterizationTest : ResourceMetadataDaoCharacterization() {
    override val backend = SqlDelightBackend
}
