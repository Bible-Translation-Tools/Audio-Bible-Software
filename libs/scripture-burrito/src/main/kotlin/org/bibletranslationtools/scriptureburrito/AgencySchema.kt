package org.bibletranslationtools.scriptureburrito

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName



@Serializable(with = AgenciesSerializer::class)
class Agencies: ArrayList<AgencySchema>()

@Serializable
class AgencySchema(
    @SerialName("id")
    var id: String,

    @SerialName("name")
    var name: LocalizedText,

    @SerialName("roles")
    var roles: MutableList<Role> = ArrayList()
) {
    @SerialName("abbr")
    var abbr: LocalizedText? = null

    @SerialName("url")
    var url: String? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AgencySchema

        if (id != other.id) return false
        if (name != other.name) return false
        if (roles != other.roles) return false
        if (abbr != other.abbr) return false
        if (url != other.url) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + roles.hashCode()
        result = 31 * result + (abbr?.hashCode() ?: 0)
        result = 31 * result + (url?.hashCode() ?: 0)
        return result
    }
}
