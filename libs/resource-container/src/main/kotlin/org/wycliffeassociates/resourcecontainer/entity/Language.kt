package org.wycliffeassociates.resourcecontainer.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
data class Language(
        var direction: String = "",
        var identifier: String = "",
        var title: String = ""
)

fun language(init: Language.() -> Unit): Language {
    val lang = Language()
    lang.init()
    return lang
}