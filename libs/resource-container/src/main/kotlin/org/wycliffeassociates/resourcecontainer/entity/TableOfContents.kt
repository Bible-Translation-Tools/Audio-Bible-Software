package org.wycliffeassociates.resourcecontainer.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable



@Serializable
data class TableOfContents(
    var title: String = "",
    @SerialName("sub-title")
    var subtitle: String = "",
    var link: String = "",
    val sections: MutableList<TableOfContents> = arrayListOf()
)

fun toc(init: TableOfContents.() -> Unit): TableOfContents {
    val toc = TableOfContents()
    toc.init()
    return toc
}