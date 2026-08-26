package org.bibletranslationtools.scriptureburrito

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

import java.util.HashMap
@Serializable(with = IdAuthoritiesSchemaSerializer::class)
class IdAuthoritiesSchema: HashMap<String, IdAuthority>()

@Serializable
class IdAuthority {
    @SerialName("id")
    var id: String? = null

    @SerialName("name")
    var name: HashMap<String, String> = hashMapOf()
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as IdAuthority

        if (id != other.id) return false
        if (name != other.name) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id?.hashCode() ?: 0
        result = 31 * result + name.hashCode()
        return result
    }

    override fun toString(): String {
        return "IdAuthority(id=$id, name=$name)"
    }
}