package ch.nokillswit.infra

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

/** Use for JSON body IDs. The route still decides the acceptable ID range and whether the resource exists. */
typealias JsonUInt = @Serializable(StrictUIntSerializer::class) UInt
typealias JsonInt = @Serializable(StrictIntSerializer::class) Int

private val DECIMAL_INTEGER = Regex("-?(0|[1-9][0-9]*)")

private fun Decoder.jsonInteger(): String {
    val json = this as? JsonDecoder ?: throw SerializationException("Expected a JSON integer")
    val value = json.decodeJsonElement() as? JsonPrimitive
    if (value == null || value.isString || !DECIMAL_INTEGER.matches(value.content)) {
        throw SerializationException("Expected a JSON integer")
    }
    return value.content
}

internal object StrictUIntSerializer : KSerializer<UInt> {
    override val descriptor = UInt.serializer().descriptor

    override fun serialize(encoder: Encoder, value: UInt) = UInt.serializer().serialize(encoder, value)

    override fun deserialize(decoder: Decoder): UInt =
        decoder.jsonInteger().toUIntOrNull() ?: throw SerializationException("An ID is out of range")
}

internal object StrictIntSerializer : KSerializer<Int> {
    override val descriptor = Int.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)

    override fun deserialize(decoder: Decoder): Int =
        decoder.jsonInteger().toIntOrNull() ?: throw SerializationException("An ID is out of range")
}
