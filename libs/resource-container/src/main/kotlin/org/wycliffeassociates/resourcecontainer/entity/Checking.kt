package org.wycliffeassociates.resourcecontainer.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
data class Checking(
    @SerialName("checking_entity")
    var checkingEntity: List<String> = arrayListOf(),
    @SerialName("checking_level")
    var checkingLevel: String = ""
)

fun checking(init: Checking.() -> Unit): Checking {
    val checking = Checking()
    checking.init()
    return checking
}