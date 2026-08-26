package org.bibletranslationtools.scriptureburrito

import kotlinx.serialization.Serializable

@Serializable(with = ScopeSchemaSerializer::class)
class ScopeSchema: HashMap<String, MutableList<String>>()