package org.bibletranslationtools.scriptureburrito

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName


@Serializable
enum class Role(private val value: String) {
    @SerialName("rightsAdmin")
    RIGHTS_ADMIN("rightsAdmin"),
    @SerialName("rightsHolder")
    RIGHTS_HOLDER("rightsHolder"),
    @SerialName("content")
    CONTENT("content"),
    @SerialName("publication")
    PUBLICATION("publication"),
    @SerialName("management")
    MANAGEMENT("management"),
    @SerialName("finance")
    FINANCE("finance"),
    @SerialName("qa")
    QA("qa");

    override fun toString(): String {
        return this.value
    }

    fun value(): String {
        return this.value
    }

    companion object {
        private val CONSTANTS: MutableMap<String, Role> = HashMap()

        init {
            for (c in values()) {
                CONSTANTS[c.value] = c
            }
        }

        fun fromValue(value: String): Role {
            val constant = CONSTANTS[value]
            requireNotNull(constant) { value }
            return constant
        }
    }
}
