package org.bibletranslationtools.scriptureburrito.flavor.gloss

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.SerialName


@Serializable
class TextStoriesSchema {
    @SerialName("name")
    var name: JsonElement? = null
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TextStoriesSchema) return false

        if (name != other.name) return false

        return true
    }

    override fun hashCode(): Int {
        return name?.hashCode() ?: 0
    }

    override fun toString(): String {
        return "TextStoriesSchema(name=$name)"
    }
}
