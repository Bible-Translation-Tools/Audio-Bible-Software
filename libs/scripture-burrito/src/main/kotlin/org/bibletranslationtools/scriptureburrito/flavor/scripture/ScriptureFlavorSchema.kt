package org.bibletranslationtools.scriptureburrito.flavor.scripture

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

import org.bibletranslationtools.scriptureburrito.flavor.FlavorSchema

@Serializable
class ScriptureFlavorSchema: FlavorSchema() {
    @SerialName("usfmVersion")
    var usfmVersion: String? = null

    @SerialName("translationType")
    var translationType: String? = null

    @SerialName("audience")
    var audience: String? = null

    @SerialName("projectType")
    var projectType: String? = null
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScriptureFlavorSchema) return false
        if (!super.equals(other)) return false

        if (usfmVersion != other.usfmVersion) return false
        if (translationType != other.translationType) return false
        if (audience != other.audience) return false
        if (projectType != other.projectType) return false

        return true
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + (usfmVersion?.hashCode() ?: 0)
        result = 31 * result + (translationType?.hashCode() ?: 0)
        result = 31 * result + (audience?.hashCode() ?: 0)
        result = 31 * result + (projectType?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "ScriptureFlavorSchema(usfmVersion=$usfmVersion, translationType=$translationType, audience=$audience, projectType=$projectType)"
    }
}
