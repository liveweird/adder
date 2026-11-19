package ch.nokillswit.threads

import ch.nokillswit.infra.paging.PageResponse
import kotlinx.serialization.Serializable

/**
 * One hashtag of a room (`GET /rooms/{id}/tags`): the FOLDED [tag] (lower case, accents stripped: the key the filter takes, not the
 * spelling a message used) and the number of distinct threads (open and closed) that have a live message carrying it.
 */
@Serializable
data class RoomTagResponse(val tag: String, val threadCount: Long)

typealias RoomTagPageResponse = PageResponse<RoomTagResponse>

/** One page of a room's tags plus the total number of tags after the filter. */
data class RoomTagListResult(val items: List<RoomTagResponse>, val total: Long)

/** The tag list's one filter: a case- and accent-insensitive substring of the tag (for a picker), at most [MAX_TAG_FILTER_LENGTH] long. */
data class RoomTagListFilter(val tag: String? = null)

/** The longest `tag` substring of the room tag list, in code points: a conservative cap (a tag is written with 50, a key runs to 150). */
const val MAX_TAG_FILTER_LENGTH = MAX_TAG_LENGTH

/** The folded key is cut to this (V15 `tag VARCHAR(150)`): folding can lengthen a tag, and an overflow must never fail a write. */
const val MAX_STORED_TAG_LENGTH = 150
