package org.bibletranslationtools.scriptureburrito

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName



@Serializable
class ProgressSchema {
    @SerialName("dateStarted")
    var dateStarted: String? = null

    @SerialName("dateCompleted")
    var dateCompleted: String? = null
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProgressSchema) return false

        if (dateStarted != other.dateStarted) return false
        if (dateCompleted != other.dateCompleted) return false

        return true
    }

    override fun hashCode(): Int {
        var result = dateStarted?.hashCode() ?: 0
        result = 31 * result + (dateCompleted?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "ProgressSchema(dateStarted=$dateStarted, dateCompleted=$dateCompleted)"
    }
}
