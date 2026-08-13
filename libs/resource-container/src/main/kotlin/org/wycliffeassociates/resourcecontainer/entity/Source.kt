package org.wycliffeassociates.resourcecontainer.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
data class Source(
        var identifier: String = "",
        var language: String = "",
        var version: String = ""
)

fun source(init: Source.() -> Unit): Source {
    val source = Source()
    source.init()
    return source
}