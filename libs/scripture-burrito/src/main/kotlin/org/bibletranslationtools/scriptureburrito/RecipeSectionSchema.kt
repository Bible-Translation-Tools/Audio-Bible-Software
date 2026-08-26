package org.bibletranslationtools.scriptureburrito

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.SerialName


@Serializable
class RecipeSectionSchema {

    @SerialName("type")
    var type: JsonElement? = null

    @SerialName("nameId")
    var nameId: String? = null

    @SerialName("content")
    var content: MutableList<JsonElement> = ArrayList()
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RecipeSectionSchema) return false

        if (type != other.type) return false
        if (nameId != other.nameId) return false
        if (content != other.content) return false

        return true
    }

    override fun hashCode(): Int {
        var result = type?.hashCode() ?: 0
        result = 31 * result + (nameId?.hashCode() ?: 0)
        result = 31 * result + content.hashCode()
        return result
    }

    override fun toString(): String {
        return "RecipeSectionSchema(type=$type, nameId=$nameId, content=$content)"
    }
}
