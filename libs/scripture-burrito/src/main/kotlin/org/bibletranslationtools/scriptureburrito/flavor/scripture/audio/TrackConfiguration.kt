package org.bibletranslationtools.scriptureburrito.flavor.scripture.audio

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName


@Serializable
enum class TrackConfiguration(private val value: String) {
    @SerialName("1/0 (Mono)")
    MONO("1/0 (Mono)"),
    @SerialName("Dual mono")
    DUAL_MONO("Dual mono"),
    @SerialName("2/0 (Stereo)")
    STEREO("2/0 (Stereo)"),
    @SerialName("5.1 Surround")
    SURROUND("5.1 Surround");

    override fun toString(): String {
        return this.value
    }

    fun value(): String {
        return this.value
    }

    companion object {
        private val CONSTANTS: MutableMap<String, TrackConfiguration> = HashMap()

        init {
            for (c in values()) {
                CONSTANTS[c.value] = c
            }
        }

        fun fromValue(value: String): TrackConfiguration {
            val constant = CONSTANTS[value]
            requireNotNull(constant) { value }
            return constant
        }
    }
}