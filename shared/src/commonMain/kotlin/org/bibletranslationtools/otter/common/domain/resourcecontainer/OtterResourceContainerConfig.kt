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
package org.bibletranslationtools.otter.common.domain.resourcecontainer

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.wycliffeassociates.resourcecontainer.Config
import java.io.OutputStream
import java.io.Reader

/**
 * config.yaml inside a resource container. Mirrors the codec in :libs:resource-container —
 * strictMode = false for unknown keys, encodeDefaults = true for the old Include.NON_NULL.
 */
private val CONFIG_YAML = Yaml(
    configuration = YamlConfiguration(strictMode = false, encodeDefaults = true)
)

class OtterResourceContainerConfig : Config {
    var config: OtterConfig? = null
    var extendedDublinCore: ExtendedDublinCore? = null

    override fun read(reader: Reader): Config {
        config = reader.use {
            CONFIG_YAML.decodeFromString(OtterConfig.serializer(), it.readText())
        }
        config?.let {
            extendedDublinCore = it.extendedDublinCore
        }
        return this
    }

    override fun write(writer: OutputStream) {
        config?.let {
            writer.write(CONFIG_YAML.encodeToString(OtterConfig.serializer(), it).toByteArray())
            writer.flush()
        }
    }
}

@Serializable
class OtterConfig(
    @SerialName("extended_dublin_core")
    var extendedDublinCore: ExtendedDublinCore
)

@Serializable
class ExtendedDublinCore(
    var categories: List<Category>
)

@Serializable
data class Category(
    val identifier: String,
    val title: String,
    val type: String,
    val sort: Int
)
