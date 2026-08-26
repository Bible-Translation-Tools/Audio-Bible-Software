package org.bibletranslationtools.scriptureburrito.flavor

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

import org.bibletranslationtools.scriptureburrito.Flavor
import org.bibletranslationtools.scriptureburrito.ScopeSchema

@Serializable
class FlavorType(
    @SerialName("name")
    var name: Flavor,

    @SerialName("flavor")
    var flavor: FlavorSchema,

    @SerialName("currentScope")
    var currentScope: ScopeSchema
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FlavorType) return false

        if (name != other.name) return false
        if (flavor != other.flavor) return false
        if (currentScope != other.currentScope) return false

        return true
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + (flavor?.hashCode() ?: 0)
        result = 31 * result + (currentScope?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "FlavorType(name=$name, flavor=$flavor, currentScope=$currentScope)"
    }
}