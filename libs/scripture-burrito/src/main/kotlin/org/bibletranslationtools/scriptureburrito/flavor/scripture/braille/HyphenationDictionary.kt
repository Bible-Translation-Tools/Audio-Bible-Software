package org.bibletranslationtools.scriptureburrito.flavor.scripture.braille

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName


@Serializable
class HyphenationDictionary {

    @SerialName("src")
    var src: String? = null

    @SerialName("name")
    var name: String? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HyphenationDictionary) return false

        if (src != other.src) return false
        if (name != other.name) return false

        return true
    }

    override fun hashCode(): Int {
        var result = src?.hashCode() ?: 0
        result = 31 * result + (name?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "HyphenationDictionary(src=$src, name=$name)"
    }
}
