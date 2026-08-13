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
package org.bibletranslationtools.otter.common.data

import org.bibletranslationtools.otter.common.data.primitives.Content
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder


typealias Chunkification = HashMap<Int, List<Content>>

/**
 * chunks.json is a map of chapter number to its chunk [Content]s. kotlinx needs the serializer
 * spelled out because Chunkification is a typealias, not a class it can look up.
 */
val CHUNKIFICATION: KSerializer<Chunkification> = object : KSerializer<Chunkification> {
    private val delegate = MapSerializer(Int.serializer(), ListSerializer(Content.serializer()))
    override val descriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: Chunkification) = delegate.serialize(encoder, value)
    override fun deserialize(decoder: Decoder): Chunkification =
        Chunkification().apply { putAll(delegate.deserialize(decoder)) }
}
