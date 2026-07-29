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
package org.bibletranslationtools.otter.common.persistence.repositories.mapping

import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.workbook.Translation
import org.bibletranslationtools.otter.common.persistence.entities.TranslationEntity
import java.time.LocalDateTime
import javax.inject.Inject

class TranslationMapper @Inject constructor() {
    fun mapFromEntity(type: TranslationEntity, source: Language, target: Language) =
        Translation(
            source,
            target,
            type.modifiedTs?.let(LocalDateTime::parse),
            type.sourceRate,
            type.targetRate,
            type.id
        )

    fun mapToEntity(type: Translation): TranslationEntity {
        return TranslationEntity(
            type.id,
            type.source.id,
            type.target.id,
            type.modifiedTs?.toString(),
            type.sourceRate,
            type.targetRate
        )
    }
}
