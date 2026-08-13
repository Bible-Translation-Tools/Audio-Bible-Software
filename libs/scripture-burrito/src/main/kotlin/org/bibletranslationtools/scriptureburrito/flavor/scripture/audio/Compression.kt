package org.bibletranslationtools.scriptureburrito.flavor.scripture.audio

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName


@Serializable
enum class Compression(private val value: String) {
    @SerialName("mp3")
    MP3("mp3"),
    @SerialName("wav")
    WAV("wav"),
    @SerialName("ogg")
    OGG("ogg");

    override fun toString(): String {
        return this.value
    }

    fun value(): String {
        return this.value
    }

    companion object {
        private val CONSTANTS: MutableMap<String, Compression> = HashMap()

        init {
            for (c in values()) {
                CONSTANTS[c.value] = c
            }
        }

        fun fromValue(value: String): Compression {
            val constant = CONSTANTS[value]
            requireNotNull(constant) { value }
            return constant
        }
    }
}