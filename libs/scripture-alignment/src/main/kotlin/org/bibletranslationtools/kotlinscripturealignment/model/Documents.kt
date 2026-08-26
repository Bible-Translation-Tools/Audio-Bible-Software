package org.bibletranslationtools.kotlinscripturealignment.model

import kotlinx.serialization.Serializable
import org.bibletranslationtools.kotlinscripturealignment.serializers.DocumentsSerializer

/**
 * `documents` is a content-based union: a JSON ARRAY of references, or an OBJECT keyed by id.
 * kotlinx cannot infer that from the type alone, so [DocumentsSerializer] inspects the element
 * shape exactly as the old StdDeserializer inspected isArray/isObject.
 */
@Serializable(with = DocumentsSerializer::class)
sealed interface Documents

@Serializable(with = DocumentsSerializer::class)
data class DocumentsList(val list: List<DocumentReference>) : Documents

@Serializable(with = DocumentsSerializer::class)
data class DocumentsMap(val map: Map<String, DocumentReference>) : Documents
