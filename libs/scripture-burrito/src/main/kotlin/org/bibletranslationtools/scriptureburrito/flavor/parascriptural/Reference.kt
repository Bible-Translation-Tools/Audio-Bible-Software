package org.bibletranslationtools.scriptureburrito.flavor.parascriptural

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName


@Serializable
class Reference {
    @SerialName("start")
    var start: Double? = null

    @SerialName("end")
    var end: Double? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Reference) return false

        if (start != other.start) return false
        if (end != other.end) return false

        return true
    }

    override fun hashCode(): Int {
        var result = start?.hashCode() ?: 0
        result = 31 * result + (end?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "Reference(start=$start, end=$end)"
    }
}
