package org.bibletranslationtools.scriptureburrito

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.SerialName
import kotlinx.serialization.Transient



@Serializable
class IdentificationSchema {

    @SerialName("name")
    var name: HashMap<String, String> = hashMapOf()

    @SerialName("description")
    var description: HashMap<String, String> = hashMapOf()

    @SerialName("abbreviation")
    var abbreviation: HashMap<String, String> = hashMapOf()

    @SerialName("primary")
    @Transient
    var primary: PrimaryIdentification = PrimaryIdentification()

    @SerialName("upstream")
    var upstream: JsonElement? = null
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as IdentificationSchema

        if (name != other.name) return false
        if (description != other.description) return false
        if (abbreviation != other.abbreviation) return false
        if (primary != other.primary) return false
        if (upstream != other.upstream) return false

        return true
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + (description.hashCode())
        result = 31 * result + (abbreviation.hashCode())
        result = 31 * result + primary.hashCode()
        result = 31 * result + (upstream?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "IdentificationSchema(name=$name, description=$description, abbreviation=$abbreviation, primary=$primary, upstream=$upstream)"
    }
}