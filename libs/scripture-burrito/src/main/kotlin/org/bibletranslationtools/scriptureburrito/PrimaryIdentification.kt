package org.bibletranslationtools.scriptureburrito

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement



/**
 * Due to Primary being json objects of arbitrary schema, use a JsonElement
 */
@Serializable(with = PrimaryIdentificationSerializer::class)
class PrimaryIdentification: HashMap<String, JsonElement>()
