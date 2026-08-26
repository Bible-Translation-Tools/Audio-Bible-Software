package org.bibletranslationtools.scriptureburrito

import kotlinx.serialization.Serializable

import java.util.HashMap

@Serializable(with = IngredientsSchemaSerializer::class)
class IngredientsSchema: HashMap<String, IngredientSchema>()
