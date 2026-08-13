package org.bibletranslationtools.scriptureburrito

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

import org.bibletranslationtools.scriptureburrito.Format


@Serializable
class LocalizedText(
    @SerialName("short")
    var short: HashMap<String, String>
) {
    @SerialName("abbr")
    var abbr: HashMap<String, String> = HashMap()

    @SerialName("long")
    var long: HashMap<String, String> = HashMap()
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as LocalizedText

        if (short != other.short) return false
        if (abbr != other.abbr) return false
        if (long != other.long) return false

        return true
    }

    override fun hashCode(): Int {
        var result = short.hashCode()
        result = 31 * result + abbr.hashCode()
        result = 31 * result + long.hashCode()
        return result
    }

    override fun toString(): String {
        return "LocalizedText(short=$short, abbr=$abbr, long=$long)"
    }
}
