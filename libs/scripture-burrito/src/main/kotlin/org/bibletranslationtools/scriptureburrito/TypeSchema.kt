package org.bibletranslationtools.scriptureburrito

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

import org.bibletranslationtools.scriptureburrito.flavor.FlavorType


@Serializable
class TypeSchema(
    @SerialName("flavorType")
    var flavorType: FlavorType
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TypeSchema

        return flavorType == other.flavorType
    }

    override fun hashCode(): Int {
        return flavorType.hashCode()
    }

    override fun toString(): String {
        return "TypeSchema(${flavorType})"
    }
}
