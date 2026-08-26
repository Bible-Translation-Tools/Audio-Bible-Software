package org.bibletranslationtools.scriptureburrito.flavor.scripture.audio

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName


@Serializable
enum class Performance(private val value: String) {
    @SerialName("singleVoice")
    SINGLE_VOICE("singleVoice"),
    @SerialName("multipleVoice")
    MULTIPLE_VOICE("multipleVoice"),
    @SerialName("reading")
    READING("reading"),
    @SerialName("drama")
    DRAMA("drama"),
    @SerialName("withMusic")
    WITH_MUSIC("withMusic"),
    @SerialName("withEffects")
    WITH_EFFECTS("withEffects"),
    @SerialName("withHeadings")
    WITH_HEADINGS("withHeadings");

    override fun toString(): String {
        return this.value
    }

    fun value(): String {
        return this.value
    }

    companion object {
        private val CONSTANTS: MutableMap<String, Performance> = HashMap()

        init {
            for (c in values()) {
                CONSTANTS[c.value] = c
            }
        }

        fun fromValue(value: String): Performance {
            val constant = CONSTANTS[value]
            requireNotNull(constant) { value }
            return constant
        }
    }
}
