package org.bibletranslationtools.scriptureburrito.flavor.scripture.audio

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

import org.bibletranslationtools.scriptureburrito.flavor.FlavorSchema

@Serializable
@SerialName("audioTranslation")
class AudioFlavorSchema() : FlavorSchema() {

    @SerialName("conventions")
    private var conventions: AudioConventions? = null

    fun getAudioConventions(): AudioConventions? {
        return conventions
    }

    fun setAudioConventions(conventions: AudioConventions?) {
        this.conventions = conventions
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioFlavorSchema) return false
        if (!super.equals(other)) return false

        if (conventions != other.conventions) return false

        return true
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + (conventions?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "AudioFlavorSchema(name=audioTranslation, conventions=$conventions)"
    }
}
