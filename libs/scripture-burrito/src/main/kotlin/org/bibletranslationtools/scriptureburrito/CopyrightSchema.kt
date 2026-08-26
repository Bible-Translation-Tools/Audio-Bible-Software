package org.bibletranslationtools.scriptureburrito

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName



@Serializable
class CopyrightSchema {

    @SerialName("licenses")
    var licenses: MutableList<License>? = ArrayList()
    
    @SerialName("publicDomain")
    var publicDomain: Boolean? = null
    
    @SerialName("shortStatements")
    var shortStatements: MutableList<ShortStatement> = ArrayList()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CopyrightSchema

        if (licenses != other.licenses) return false
        if (publicDomain != other.publicDomain) return false
        if (shortStatements != other.shortStatements) return false

        return true
    }

    override fun hashCode(): Int {
        var result = licenses?.hashCode() ?: 0
        result = 31 * result + (publicDomain?.hashCode() ?: 0)
        result = 31 * result + shortStatements.hashCode()
        return result
    }

    override fun toString(): String {
        return "CopyrightSchema(licenses=$licenses, publicDomain=$publicDomain, shortStatements=$shortStatements)"
    }
}
