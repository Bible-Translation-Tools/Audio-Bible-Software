package org.bibletranslationtools.scriptureburrito

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.SerialName



@Serializable
class RecipeElementSchema {
    @SerialName("type")
    var type: JsonElement? = null

    @SerialName("nameId")
    var nameId: String? = null
    
    @SerialName("ingredient")
    var ingredient: String? = null
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RecipeElementSchema) return false

        if (type != other.type) return false
        if (nameId != other.nameId) return false
        if (ingredient != other.ingredient) return false

        return true
    }

    override fun hashCode(): Int {
        var result = type?.hashCode() ?: 0
        result = 31 * result + (nameId?.hashCode() ?: 0)
        result = 31 * result + (ingredient?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "RecipeElementSchema(type=$type, nameId=$nameId, ingredient=$ingredient)"
    }
}
