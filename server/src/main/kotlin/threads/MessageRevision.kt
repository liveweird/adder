package ch.nokillswit.threads

import ch.nokillswit.infra.paging.PageResponse
import kotlinx.serialization.Serializable

/** One prior body of a message, retained only until the message or its thread is deleted. */
@Serializable
data class MessageRevisionResponse(
    val id: UInt,
    val body: String,
    val createdAt: Long,
    val supersededAt: Long,
)

typealias MessageRevisionPageResponse = PageResponse<MessageRevisionResponse>
