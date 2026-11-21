package ch.nokillswit.threads

import ch.nokillswit.infra.validation.requireNoDuplicates
import ch.nokillswit.tags.normalizedTagName
import ch.nokillswit.tags.requireValidTagName
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable

/**
 * One curated tag on a thread (V33, 1.2 D3): the registry name and whether the registry has RETIRED it since (1.2 A6: a retired tag stays
 * on its threads, struck through in the SPA, until the thread's next tag save drops it).
 */
@Serializable
data class ThreadTag(val name: String, val retired: Boolean)

/**
 * `PUT /rooms/{id}/threads/{number}/tags` under `X-Expected-Revision`: the thread's WHOLE tag set (registry names, at most
 * [MAX_THREAD_TAGS], each once). An empty list clears the thread. The set is applied as a diff; the same set again is a `204` that writes
 * nothing.
 */
@Serializable
data class ThreadTagsRequest(val tags: List<String>)

/** The most tags one thread carries (Hammurabi's ADR cap). */
const val MAX_THREAD_TAGS = 20

/**
 * The request in its canonical form: [normalizedTagName] on every name. The count is judged BEFORE any name is normalised, so an
 * oversized list never costs a pass of sanitising (Hammurabi's rule); the route AND the service run this.
 */
fun normalizedThreadTags(request: ThreadTagsRequest): ThreadTagsRequest {
    requireTagCount(request.tags)
    return ThreadTagsRequest(request.tags.map(::normalizedTagName))
}

private fun requireTagCount(tags: List<String>) {
    if (tags.size > MAX_THREAD_TAGS) throw BadRequestException("A thread carries at most $MAX_THREAD_TAGS tags")
}

/** The set's SHAPE: at most [MAX_THREAD_TAGS], each a valid normalised name, none twice. Whether a name is LIVE is the service's. */
fun validateThreadTags(request: ThreadTagsRequest) {
    requireTagCount(request.tags)
    request.tags.forEach(::requireValidTagName)
    requireNoDuplicates(request.tags, "Tags must not repeat")
}

/** What one tag save changed (the event and the audit line both read it); both lists are in code point order. */
data class TagDiff(val added: List<String>, val removed: List<String>) {
    val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty()
}

/** What a tag save wrote, for the route's audit line: the row id, the NEW [revision] and the [diff]. */
data class ThreadTagChange(val threadId: UInt, val revision: Long, val diff: TagDiff)

/** The lock-free read the route's steward pre-check needs: who owns the thread and whether it is closed. */
data class ThreadStewardView(val ownerId: UInt, val closed: Boolean)

/**
 * The thread's tags changed: [added] and [removed] as the registry names, each list joined with ", " (a tag name holds no comma, so the
 * SPA splits it back). A side with no change has no key (`chat-model.md` "History"; Hammurabi's `adrTagsChangedEvent`).
 */
fun threadTagsChangedEvent(added: List<String>, removed: List<String>) = ThreadEventDescriptor(
    ThreadEventType.TAGS_CHANGED,
    buildMap {
        if (added.isNotEmpty()) put("added", added.joinToString(", "))
        if (removed.isNotEmpty()) put("removed", removed.joinToString(", "))
    },
)
