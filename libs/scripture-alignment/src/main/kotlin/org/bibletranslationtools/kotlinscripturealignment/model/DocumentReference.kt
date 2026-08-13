package org.bibletranslationtools.kotlinscripturealignment.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `docid` was @JsonInclude(NON_NULL); the shared Json instance sets explicitNulls = false, which
 * omits a null the same way rather than writing `"docid": null`.
 */
@Serializable
class DocumentReference(
    @SerialName("scheme")
    val scheme: String,
    @SerialName("docid")
    val docid: String? = null
)
