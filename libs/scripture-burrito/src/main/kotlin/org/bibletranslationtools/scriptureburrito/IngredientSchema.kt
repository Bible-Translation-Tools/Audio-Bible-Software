package org.bibletranslationtools.scriptureburrito

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.SerialName



@Serializable
class IngredientSchema {
    
    @SerialName("size")
    var size: Int? = null
    
    @SerialName("lang")
    var lang: String? = null

    @SerialName("mimeType")
    var mimeType: String? = null

    @SerialName("checksum")
    var checksum: Checksum? = null

    @SerialName("scope")
    var scope: ScopeSchema? = null

    @SerialName("role")
    var role: String? = null

    @SerialName("properties")
    // Was Map<String, Any> under Jackson, which mapped arbitrary JSON onto Object.
    // JsonElement is the kotlinx equivalent; no caller in this build reads it.
    var properties: Map<String, JsonElement>? = null


    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as IngredientSchema

        if (size != other.size) return false
        if (lang != other.lang) return false
        if (mimeType != other.mimeType) return false
        if (checksum != other.checksum) return false
        if (scope != other.scope) return false
        if (role != other.role) return false

        return true
    }

    override fun hashCode(): Int {
        var result = size ?: 0
        result = 31 * result + (lang?.hashCode() ?: 0)
        result = 31 * result + (mimeType?.hashCode() ?: 0)
        result = 31 * result + (checksum?.hashCode() ?: 0)
        result = 31 * result + (scope?.hashCode() ?: 0)
        result = 31 * result + (role?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "IngredientSchema(size=$size, lang=$lang, mimeType=$mimeType, checksum=$checksum, scope=$scope, role=$role)"
    }
}
