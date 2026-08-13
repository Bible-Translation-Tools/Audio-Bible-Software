package org.bibletranslationtools.scriptureburrito.flavor

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName



@Serializable
class XFlavorSchema {
    @SerialName("name")
    var name: String? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is XFlavorSchema) return false

        if (name != other.name) return false

        return true
    }

    override fun hashCode(): Int {
        return name?.hashCode() ?: 0
    }

    override fun toString(): String {
        return "XFlavorSchema(name=$name)"
    }
}
