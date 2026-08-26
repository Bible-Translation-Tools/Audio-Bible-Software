package org.bibletranslationtools.kotlinscripturealignment.model

import kotlinx.serialization.Serializable
import org.bibletranslationtools.kotlinscripturealignment.serializers.GroupSerializer

@Serializable(with = GroupSerializer::class)
data class Group(
    val documents: Documents? = null,
    val records: List<Record> = listOf()
)
