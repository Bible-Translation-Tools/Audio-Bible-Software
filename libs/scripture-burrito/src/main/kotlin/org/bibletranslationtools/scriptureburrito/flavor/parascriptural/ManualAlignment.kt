package org.bibletranslationtools.scriptureburrito.flavor.parascriptural

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName


@Serializable
class ManualAlignment {

    @SerialName("user")
    var user: String? = null

    @SerialName("references")
    private var references: MutableList<Reference>? = ArrayList<Reference>()

    fun getReferences(): MutableList<Reference>? {
        return references
    }

    fun setReferences(references: MutableList<Reference>?) {
        this.references = references
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ManualAlignment) return false

        if (user != other.user) return false
        if (references != other.references) return false

        return true
    }

    override fun hashCode(): Int {
        var result = user?.hashCode() ?: 0
        result = 31 * result + (references?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "ManualAlignment(user=$user, references=$references)"
    }


}
