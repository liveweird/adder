package ch.nokillswit.live

import ch.nokillswit.infra.maskedSecret
import ch.nokillswit.infra.notify.LiveKind
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** The protocol version announced in `ready`; the spec's `LiveReadyFrame.protocol`. */
const val LIVE_PROTOCOL_VERSION = 1

/** Subscriptions one socket may hold, announced in `ready` (`maxSubscriptions`); the same number bounds the ids of one frame. */
const val MAX_SUBSCRIPTIONS = 128

/**
 * The frames a client sends (`live.md` "Frames and close codes"; the spec's `LiveClientFrame`): JSON text, discriminated by `type`, parsed
 * STRICTLY - an unknown type, an unknown field, a missing field or a wrong type is a [SerializationException] and the socket closes 1008.
 * Ids arrive as `Long` so a negative or out-of-range number is rejected by [validIds] instead of wrapping into a `UInt`.
 */
@Serializable
sealed class LiveClientFrame {
    /** The first frame, exactly once, carrying the single-use ticket. Never logged: [toString] masks it. */
    @Serializable
    @SerialName("auth")
    data class Auth(val ticket: String) : LiveClientFrame() {
        override fun toString(): String = "Auth(ticket=${maskedSecret(ticket)})"
    }

    @Serializable
    @SerialName("subscribe")
    data class Subscribe(val roomIds: List<Long>) : LiveClientFrame()

    @Serializable
    @SerialName("unsubscribe")
    data class Unsubscribe(val roomIds: List<Long>) : LiveClientFrame()

    @Serializable
    @SerialName("ping")
    data object Ping : LiveClientFrame()
}

/**
 * The room ids of a `subscribe` or `unsubscribe`: 1..[MAX_SUBSCRIPTIONS] positive ids of a `UInt`, de-duplicated; null when the frame
 * breaks the rule.
 */
fun validIds(roomIds: List<Long>): List<UInt>? {
    if (roomIds.isEmpty() || roomIds.size > MAX_SUBSCRIPTIONS) return null
    if (roomIds.any { it < 1 || it > UInt.MAX_VALUE.toLong() }) return null
    return roomIds.map { it.toUInt() }.distinct()
}

/**
 * The frames the server sends (the spec's `LiveServerFrame`). A hint says THAT something changed and never what, so the event carries
 * ids only; the client ignores unknown types and fields, which is what lets the protocol grow.
 */
@Serializable
sealed class LiveServerFrame {
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    @SerialName("ready")
    data class Ready(
        @EncodeDefault val protocol: Int = LIVE_PROTOCOL_VERSION,
        @EncodeDefault val maxSubscriptions: Int = MAX_SUBSCRIPTIONS,
    ) : LiveServerFrame()

    @Serializable
    @SerialName("subscribed")
    data class Subscribed(val roomIds: List<UInt>, val refused: List<UInt>, val capped: List<UInt>) : LiveServerFrame()

    @Serializable
    @SerialName("event")
    data class Event(val kind: LiveKind, val roomId: UInt, val threadNumber: Int? = null, val messageId: UInt? = null) : LiveServerFrame()

    @Serializable
    @SerialName("revoked")
    data class Revoked(val roomId: UInt) : LiveServerFrame()

    @Serializable
    @SerialName("resync")
    data object Resync : LiveServerFrame()

    @Serializable
    @SerialName("pong")
    data object Pong : LiveServerFrame()
}

/** Strict on the way in (no unknown keys), lean on the way out (nulls omitted); the discriminator is `type` for both directions. */
private val frameJson = Json {
    classDiscriminator = "type"
    explicitNulls = false
    ignoreUnknownKeys = false
}

/** Parses a client text frame; throws [SerializationException] (or [IllegalArgumentException]) for anything the protocol does not allow. */
fun parseClientFrame(text: String): LiveClientFrame = frameJson.decodeFromString(LiveClientFrame.serializer(), text)

fun LiveServerFrame.toJson(): String = frameJson.encodeToString(LiveServerFrame.serializer(), this)
