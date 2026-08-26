package org.bibletranslationtools.scriptureburrito

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.SerialName



@Serializable(with = TargetAreasSerializer::class)
class TargetAreas: ArrayList<TargetAreaSchema>()

@Serializable
class TargetAreaSchema {
    @SerialName("code")
    var code: JsonElement? = null

    @SerialName("name")
    var name: LocalizedText? = null
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TargetAreaSchema) return false

        if (code != other.code) return false
        if (name != other.name) return false

        return true
    }

    override fun hashCode(): Int {
        var result = code?.hashCode() ?: 0
        result = 31 * result + (name?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "TargetAreaSchema(code=$code, name=$name)"
    }
}
