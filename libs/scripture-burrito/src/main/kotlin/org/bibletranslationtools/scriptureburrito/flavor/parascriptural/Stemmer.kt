package org.bibletranslationtools.scriptureburrito.flavor.parascriptural

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName


@Serializable
class Stemmer {
    @SerialName("name")
    var name: String? = null

    @SerialName("version")
    var version: String? = null

    @SerialName("affixes")
    var affixes: Boolean? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Stemmer) return false

        if (name != other.name) return false
        if (version != other.version) return false
        if (affixes != other.affixes) return false

        return true
    }

    override fun hashCode(): Int {
        var result = name?.hashCode() ?: 0
        result = 31 * result + (version?.hashCode() ?: 0)
        result = 31 * result + (affixes?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "Stemmer(name=$name, version=$version, affixes=$affixes)"
    }
}
