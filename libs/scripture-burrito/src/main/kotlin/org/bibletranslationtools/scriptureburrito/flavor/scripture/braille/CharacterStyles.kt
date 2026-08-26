package org.bibletranslationtools.scriptureburrito.flavor.scripture.braille

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName


@Serializable
class CharacterStyles {

    @SerialName("callerSymbol")
    var callerSymbol: String? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CharacterStyles) return false

        if (callerSymbol != other.callerSymbol) return false

        return true
    }

    override fun hashCode(): Int {
        return callerSymbol?.hashCode() ?: 0
    }

    override fun toString(): String {
        return "CharacterStyles(callerSymbol=$callerSymbol)"
    }
}
