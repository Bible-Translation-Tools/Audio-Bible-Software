package org.bibletranslationtools.scriptureburrito

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.SerialName



@Serializable
class PromotionSchema {

    @SerialName("statementPlain")
    var statementPlain: LocalizedText? = null
    
    @SerialName("statementRich")
    var statementRich: JsonElement? = null
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PromotionSchema) return false

        if (statementPlain != other.statementPlain) return false
        if (statementRich != other.statementRich) return false

        return true
    }

    override fun hashCode(): Int {
        var result = statementPlain?.hashCode() ?: 0
        result = 31 * result + (statementRich?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "PromotionSchema(statementPlain=$statementPlain, statementRich=$statementRich)"
    }
}
