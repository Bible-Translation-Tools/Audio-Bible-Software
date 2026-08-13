package org.bibletranslationtools.scriptureburrito.flavor.scripture.braille

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName


@Serializable
class Processor {

    @SerialName("name")
    var name: String? = null

    @SerialName("version")
    var version: String? = null

    @SerialName("table")
    private var table: Table? = null
    fun getTable(): Table? {
        return table
    }

    fun setTable(table: Table?) {
        this.table = table
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Processor) return false

        if (name != other.name) return false
        if (version != other.version) return false
        if (table != other.table) return false

        return true
    }

    override fun hashCode(): Int {
        var result = name?.hashCode() ?: 0
        result = 31 * result + (version?.hashCode() ?: 0)
        result = 31 * result + (table?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "Processor(name=$name, version=$version, table=$table)"
    }
}
