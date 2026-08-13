package org.bibletranslationtools.scriptureburrito.flavor.scripture.braille

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName


@Serializable
class NumberSign {

    @SerialName("character")
    var character: String? = null

    @SerialName("useInMargin")
    var useInMargin: Boolean? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NumberSign) return false

        if (character != other.character) return false
        if (useInMargin != other.useInMargin) return false

        return true
    }

    override fun hashCode(): Int {
        var result = character?.hashCode() ?: 0
        result = 31 * result + (useInMargin?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "NumberSign(character=$character, useInMargin=$useInMargin)"
    }
}
