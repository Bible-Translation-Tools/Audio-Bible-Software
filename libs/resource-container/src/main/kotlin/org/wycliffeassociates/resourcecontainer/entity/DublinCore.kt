package org.wycliffeassociates.resourcecontainer.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
data class DublinCore(
    var type: String = "",
    @SerialName("conformsto")
    var conformsTo: String = "",
    var format: String = "",
    var identifier: String = "",
    var title: String = "",
    var subject: String = "",
    var description: String = "",
    var language: Language = Language(),
    var source: MutableList<Source> = arrayListOf(),
    var rights: String = "",
    var creator: String = "",
    var contributor: MutableList<String> = arrayListOf(),
    var relation: MutableList<String> = arrayListOf(),
    var publisher: String = "",
    var issued: String = "",
    var modified: String = "",
    var version: String = ""
)

fun dublincore(init: DublinCore.() -> Unit): DublinCore {
    val dc = DublinCore()
    dc.init()
    return dc
}