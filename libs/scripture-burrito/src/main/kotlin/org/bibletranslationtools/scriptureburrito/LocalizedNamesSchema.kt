package org.bibletranslationtools.scriptureburrito

import kotlinx.serialization.Serializable

import java.util.*

@Serializable(with = LocalizedNamesSchemaSerializer::class)
class LocalizedNamesSchema: HashMap<String, LocalizedText>()

fun LocalizedNamesSchema.getBooks(): Map<String, LocalizedText> {
    return this.entries
        .filter {
            it.key.startsWith("book-")
        }
        .associate { it.key.removePrefix("book-").uppercase(Locale.US) to it.value }
}