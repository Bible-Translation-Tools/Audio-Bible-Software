package org.bibletranslationtools.scriptureburrito

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName



@Serializable(with = RelationshipsSerializer::class)
class Relationships: ArrayList<RelationshipSchema>()

@Serializable
class RelationshipSchema {
    @SerialName("relationType")
    var relationType: RelationType? = null

    @SerialName("flavor")
    var flavor: String? = null

    @SerialName("id")
    var id: String? = null
    
    @SerialName("revision")
    var revision: String? = null

    @SerialName("variant")
    var variant: String? = null

    enum class RelationType(private val value: String) {
        SOURCE("source"),
        TARGET("target"),
        EXPRESSION("expression"),
        PARASCRIPTURAL("parascriptural"),
        PERIPHERAL("peripheral");

        override fun toString(): String {
            return this.value
        }

        fun value(): String {
            return this.value
        }

        companion object {
            private val CONSTANTS: MutableMap<String, RelationType> = HashMap()

            init {
                for (c in values()) {
                    CONSTANTS[c.value] = c
                }
            }

            fun fromValue(value: String): RelationType {
                val constant = CONSTANTS[value]
                requireNotNull(constant) { value }
                return constant
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RelationshipSchema) return false

        if (relationType != other.relationType) return false
        if (flavor != other.flavor) return false
        if (id != other.id) return false
        if (revision != other.revision) return false
        if (variant != other.variant) return false

        return true
    }

    override fun hashCode(): Int {
        var result = relationType?.hashCode() ?: 0
        result = 31 * result + (flavor?.hashCode() ?: 0)
        result = 31 * result + (id?.hashCode() ?: 0)
        result = 31 * result + (revision?.hashCode() ?: 0)
        result = 31 * result + (variant?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "RelationshipSchema(relationType=$relationType, flavor=$flavor, id=$id, revision=$revision, variant=$variant)"
    }
}
