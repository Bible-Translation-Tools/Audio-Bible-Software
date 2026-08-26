package org.bibletranslationtools.scriptureburrito

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName


@Serializable
enum class Flavor(private val value: String) {
    @SerialName("scripture")
    SCRIPTURE("scripture"),
    @SerialName("gloss")
    GLOSS("gloss"),
    @SerialName("parascriptural")
    PARASCRIPTURAL("parascriptural"),
    @SerialName("peripheral")
    PERIPHERAL("peripheral");

    override fun toString(): String {
        return this.value
    }

    fun value(): String {
        return this.value
    }

    companion object {
        private val CONSTANTS: MutableMap<String, Flavor> = HashMap()

        init {
            for (c in values()) {
                CONSTANTS[c.value] = c
            }
        }

        fun fromValue(value: String): Flavor {
            val constant = CONSTANTS[value]
            requireNotNull(constant) { value }
            return constant
        }
    }
}