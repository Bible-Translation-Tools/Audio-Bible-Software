package org.bibletranslationtools.scriptureburrito

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.SerialName


@Serializable
class RecipeSchema(
    @SerialName("idAuthority")
    var idAuthority: String,

    @SerialName("operation")
    var operation: String,

    @SerialName("data")
    var data: JsonElement
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RecipeSchema

        if (idAuthority != other.idAuthority) return false
        if (operation != other.operation) return false
        if (data != other.data) return false

        return true
    }

    override fun hashCode(): Int {
        var result = idAuthority.hashCode()
        result = 31 * result + operation.hashCode()
        result = 31 * result + data.hashCode()
        return result
    }

    override fun toString(): String {
        return "RecipeSchema(idAuthority='$idAuthority', operation='$operation', data=$data)"
    }
}
