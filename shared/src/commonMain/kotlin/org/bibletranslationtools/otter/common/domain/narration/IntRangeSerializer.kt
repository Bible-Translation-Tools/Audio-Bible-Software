package org.bibletranslationtools.otter.common.domain.narration

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * `{"start":0,"end":88199}`, which is what Jackson produced for an IntRange and what existing
 * `active_verses.json` files contain. Verified against the real mapper rather than assumed —
 * kotlinx has no built-in IntRange serializer, and the obvious guesses (`first`/`last`,
 * `endInclusive`) would silently fail to load saved narration.
 */
object IntRangeSerializer : KSerializer<IntRange> {

    @Serializable
    private data class Surrogate(val start: Int, val end: Int)

    override val descriptor: SerialDescriptor = Surrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: IntRange) =
        encoder.encodeSerializableValue(Surrogate.serializer(), Surrogate(value.first, value.last))

    override fun deserialize(decoder: Decoder): IntRange =
        decoder.decodeSerializableValue(Surrogate.serializer()).let { it.start..it.end }
}
