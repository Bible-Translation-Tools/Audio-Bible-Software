package org.bibletranslationtools.scriptureburrito.flavor.scripture.audio

import kotlinx.serialization.Serializable
import org.bibletranslationtools.scriptureburrito.FormatsSerializer

@Serializable(with = FormatsSerializer::class)
class Formats: HashMap<String, AudioFormat>()