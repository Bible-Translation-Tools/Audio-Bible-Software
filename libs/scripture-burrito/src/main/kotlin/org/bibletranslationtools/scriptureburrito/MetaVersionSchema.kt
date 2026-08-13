package org.bibletranslationtools.scriptureburrito

import kotlinx.serialization.Serializable


@Serializable
enum class MetaVersionSchema(private val value: String) {
    _1_0_0("1.0.0");

    override fun toString(): String {
        return this.value
    }

    fun value(): String {
        return this.value
    }

    companion object {
        private val CONSTANTS: MutableMap<String, MetaVersionSchema> = HashMap()

        init {
            for (c in values()) {
                CONSTANTS[c.value] = c
            }
        }

        fun fromValue(value: String): MetaVersionSchema {
            val constant = CONSTANTS[value]
            requireNotNull(constant) { value }
            return constant
        }
    }
}