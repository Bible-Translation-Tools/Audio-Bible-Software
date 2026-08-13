package org.bibletranslationtools.scriptureburrito.flavor

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

import org.bibletranslationtools.scriptureburrito.flavor.scripture.ScriptureFlavorSchema
import org.bibletranslationtools.scriptureburrito.flavor.scripture.audio.AudioFlavorSchema
import org.bibletranslationtools.scriptureburrito.flavor.scripture.braille.EmbossedBrailleScriptureSchema
import org.bibletranslationtools.scriptureburrito.flavor.scripture.print.TypesetScriptureSchema
import org.bibletranslationtools.scriptureburrito.flavor.scripture.text.TextTranslationSchema
import org.bibletranslationtools.scriptureburrito.flavor.scripture.video.SignLanguageVideoTranslationSchema
import java.io.IOException

/**
 * NOT sealed: the subtypes live in sibling packages (flavor.scripture.audio, .text, .video, ...)
 * and Kotlin requires a sealed hierarchy to share one package. They are registered explicitly in
 * BurritoSerializers.burritoSerializersModule instead, which is the open-polymorphism equivalent
 * of the @JsonSubTypes list this replaces.
 */
@Serializable
@JsonClassDiscriminator("name")
abstract class FlavorSchema