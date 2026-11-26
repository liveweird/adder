package ch.nokillswit.threads

import ch.nokillswit.infra.db.jsonbContains
import ch.nokillswit.infra.paging.optionalPositiveId
import ch.nokillswit.infra.paging.positiveId
import ch.nokillswit.infra.paging.repeatedStrings
import ch.nokillswit.infra.paging.repeatedValues
import ch.nokillswit.rooms.RoomWorkflowSnapshot
import ch.nokillswit.rooms.SnapshotField
import ch.nokillswit.tags.normalizedTagName
import ch.nokillswit.tags.requireValidTagName
import ch.nokillswit.workflows.FieldType
import ch.nokillswit.workflows.requireKey
import ch.nokillswit.workflows.shown
import io.ktor.http.Parameters
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.Case
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.compoundOr
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.intLiteral
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.not

// The thread list's filters (`chat-model.md` "The thread list: filters and sorts"): parsed from the query string in the route (after the
// visibility 404, so a non-member's bad filter is still a 404) and turned into ONE predicate for the count and the rows. The `field`
// filter needs the room's workflow to be judged (its type decides the predicate), so its check runs in the service, in the list's own
// transaction, over `RoomWorkflowService.snapshot`: `threads/` never reads the workflow tables.

/** The sort name of the status's position in the room's workflow. */
const val STATUS_SORT = "status"

/** The most distinct values of `status`, of `ownerId` and of `field` a request may carry (each key is its own bound). */
const val MAX_FILTER_VALUES = 20

/** One `field=key:value` pair as the request spelled it: the key is well formed, the value is judged against the field's type later. */
data class FieldCriterion(val key: String, val value: String)

/**
 * The thread list's filters. Within a parameter the values are any-of; across parameters (and across field keys) all-of. A blank list
 * filters nothing. A well-formed but unknown status key or owner id matches nothing; an unknown, retired or unfilterable FIELD key is a
 * `400` ([predicate]). A `threadTag` is a registry tag name; `hashtag` and its compatibility alias `tag` are one folded hashtag filter;
 * one the room never saw matches nothing, as does a `mentionedId` nobody was mentioned as.
 */
data class ThreadListFilter(
    val closed: Boolean? = null,
    val statuses: List<String> = emptyList(),
    val ownerIds: List<UInt> = emptyList(),
    val fields: List<FieldCriterion> = emptyList(),
    /** Folded tags ([requireTagFilter]): a live message of the thread carries any of them. */
    val tags: List<String> = emptyList(),
    /** A live message of the thread mentions any of these people (int32 ids of 1 or more; one nobody was mentioned as matches nothing). */
    val mentionedIds: List<UInt> = emptyList(),
    /** `true`: only the threads the CALLER watches; `false`: only those they do not (a non-member watches none); null: all. */
    val watching: Boolean? = null,
    /**
     * `true`: only the threads with a LIVE message that addresses the CALLER (names them, names a team they are on NOW, names everyone, or
     * names the watchers while they watch the thread NOW); `false`: only those that do not; null: all. `mentionedId` stays direct only.
     */
    val mentionsMe: Boolean? = null,
    /**
     * Curated tag names, normalised and valid ([threadTagFilter]; 1.2 D3): the thread carries ANY of them, retired ones included (1.2 A6).
     * A name the registry never held matches nothing. Not the hashtags of [tags].
     */
    val threadTags: List<String> = emptyList(),
    /**
     * `true`: only the threads with something UNREAD for the CALLER (`unreadCount > 0`, [threadUnreadFor]); `false`: only those with
     * nothing unread; null: all. A caller with no read state (an ADMIN outside the room) has nothing unread: `true` is an empty page.
     */
    val unread: Boolean? = null,
    val mergedInto: Int? = null,
)

/** The filters of the thread list; a malformed key, id or pair, or too many values, is a `400` ([BadRequestException]). */
fun Parameters.threadListFilter(
    closed: Boolean?,
    watching: Boolean? = null,
    mentionsMe: Boolean? = null,
    unread: Boolean? = null,
): ThreadListFilter = ThreadListFilter(
    mergedInto = optionalPositiveId("mergedInto")?.toInt(),
    closed = closed,
    watching = watching,
    mentionsMe = mentionsMe,
    unread = unread,
    statuses = repeatedStrings("status", MAX_FILTER_VALUES).onEach { requireKey(it, "Status key '${it.shown()}'") },
    ownerIds = userIdFilter("ownerId"),
    fields = repeatedStrings("field", MAX_FILTER_VALUES).map(::fieldCriterion),
    tags = tagFilter(),
    mentionedIds = userIdFilter("mentionedId"),
    threadTags = threadTagFilter(),
)

/**
 * The values of the repeatable user-id parameter [name] (at most [MAX_FILTER_VALUES] distinct), 1 to int32, else a `400`. Read as strings,
 * not `repeatedLongs`: that helper takes a leading `+` and any script's digits; an id here is ASCII digits only.
 */
private fun Parameters.userIdFilter(name: String): List<UInt> = repeatedStrings(name, MAX_FILTER_VALUES).map {
    (positiveId(it) ?: throw BadRequestException("Invalid $name")).toUInt()
}.distinct()

/**
 * The combined `tag` and `hashtag` values, validated and case-folded ([requireTagFilter]); the bound of [MAX_TAG_FILTER_VALUES] counts
 * values AFTER folding (`A` and `a` are one), over a request that carries at most [MAX_RAW_TAG_VALUES] distinct raw values across both
 * names. The legacy name is read first so a tag-only request retains its validation order.
 */
private fun Parameters.tagFilter(): List<String> {
    val legacy = repeatedValues("tag")
    val aliases = repeatedValues("hashtag")
    val raw = (legacy + aliases).map { it.trim() }.distinct()
    if (raw.size > MAX_RAW_TAG_VALUES) {
        val detail = if (aliases.isEmpty()) {
            "Parameter 'tag' takes at most $MAX_RAW_TAG_VALUES values"
        } else {
            "Parameters 'tag' and 'hashtag' take at most $MAX_RAW_TAG_VALUES distinct raw tags"
        }
        throw BadRequestException(detail)
    }
    val folded = raw.map(::requireTagFilter).distinct()
    if (folded.size > MAX_TAG_FILTER_VALUES) {
        val detail = if (aliases.isEmpty()) {
            "Parameter 'tag' takes at most $MAX_TAG_FILTER_VALUES distinct tags"
        } else {
            "Parameters 'tag' and 'hashtag' take at most $MAX_TAG_FILTER_VALUES distinct folded tags"
        }
        throw BadRequestException(detail)
    }
    return folded
}

/**
 * The `threadTag` values: each normalised ([normalizedTagName]) and checked against the registry's grammar (`400` for a name no tag could
 * have, as `requireTagFilter` does for a hashtag), at most [MAX_FILTER_VALUES] distinct. The bound counts the raw values (a request carries
 * at most that many distinct ones), then names that fold together collapse (`A` and `a`). The thread list and both searches share it.
 */
internal fun Parameters.threadTagFilter(): List<String> = repeatedStrings("threadTag", MAX_FILTER_VALUES)
    .map { normalizedTagName(it).also(::requireValidTagName) }.distinct()

private fun fieldCriterion(raw: String): FieldCriterion {
    val colon = raw.indexOf(':')
    if (colon < 0) throw BadRequestException("A field filter is key:value")
    val key = raw.substring(0, colon)
    requireKey(key, "Field key '${key.shown()}'")
    return FieldCriterion(key, raw.substring(colon + 1))
}

/** The filter has a part that needs the room's workflow. */
internal val ThreadListFilter.needsWorkflow: Boolean get() = fields.isNotEmpty()


/**
 * The ONE predicate of the count and the rows, for the list of [callerUserId] (the `watching` and `mentionsMe` filters are theirs;
 * [callerIsMember] and [callerTeamIds], the mentionable teams they are on now, are what `mentionsMe` needs and are read by the service
 * only when it is asked; [unreadSince] is the caller's floor of unread counting, `unread`'s, null for a caller with no read state).
 * [workflow] is the room's snapshot and is required when [ThreadListFilter.fields] is not
 * empty; a field key that is unknown, retired or of a type that has no filter (TEXT, URL, DATE, NUMBER) is a `400`.
 */
internal fun ThreadListFilter.predicate(
    roomId: UInt,
    callerUserId: UInt,
    workflow: RoomWorkflowSnapshot?,
    callerIsMember: Boolean = false,
    callerTeamIds: List<UInt> = emptyList(),
    threadTagIds: List<UInt> = emptyList(),
    unreadSince: Long? = null,
): Op<Boolean> {
    val threads = ThreadService.Threads
    var predicate: Op<Boolean> = mergedInto?.let { mergedSources(roomId, it) }
        ?: ((threads.roomId eq roomId) and threads.canonical())
    closed?.let { predicate = predicate and (if (it) threads.closedAt.isNotNull() else threads.closedAt.isNull()) }
    if (statuses.isNotEmpty()) predicate = predicate and (threads.statusKey inList statuses)
    if (ownerIds.isNotEmpty()) predicate = predicate and (threads.ownerId inList ownerIds)
    if (mentionedIds.isNotEmpty()) predicate = predicate and threadMentionsAny(roomId, threads.id, mentionedIds)
    if (tags.isNotEmpty()) predicate = predicate and threadCarriesAnyTag(roomId, threads.id, tags)
    if (threadTags.isNotEmpty()) predicate = predicate and threadCarriesAnyThreadTag(threads.id, threadTagIds)
    watching?.let {
        val watched = threadWatchedBy(roomId, callerUserId, threads.id)
        predicate = predicate and (if (it) watched else not(watched))
    }
    unread?.let {
        // No floor = no read state (an ADMIN outside the room): nothing is unread, so `true` matches nothing and `false` everything.
        val unreadThreads = unreadSince?.let { floor -> threadUnreadFor(callerUserId, floor) } ?: Op.FALSE
        predicate = predicate and (if (it) unreadThreads else not(unreadThreads))
    }
    mentionsMe?.let {
        val addressed = threadMentionsMe(roomId, callerUserId, threads.id, callerIsMember, callerTeamIds)
        predicate = predicate and (if (it) addressed else not(addressed))
    }
    fields.groupBy { it.key }.forEach { (key, criteria) ->
        val field = workflow?.fields?.firstOrNull { it.key == key && !it.retired }
            ?: throw BadRequestException("Unknown field '${key.shown()}'")
        val documents = criteria.map { filterDocument(field, it.value) }
        predicate = predicate and documents.map { threads.fieldValues.jsonbContains(it) }.compoundOr()
    }
    return predicate
}

/**
 * The containment document of one criterion: SELECT `{"severity":"s1"}`, MULTI_SELECT `{"labels":["a"]}`, USER `{"lead":42}` (a number,
 * because `jsonb` compares numbers by value and a string never equals one). A SELECT or MULTI_SELECT value only has to be a well-formed
 * key, not a current option: a removed option still finds the threads that kept it.
 */
private fun filterDocument(field: SnapshotField, value: String): String {
    val element: JsonElement = when (field.type) {
        FieldType.SELECT -> JsonPrimitive(optionKey(field, value))
        FieldType.MULTI_SELECT -> JsonArray(listOf(JsonPrimitive(optionKey(field, value))))
        FieldType.USER -> JsonPrimitive(userId(field, value))
        else -> throw BadRequestException("Field '${field.key.shown()}' of type ${field.type} cannot be filtered")
    }
    return JsonObject(mapOf(field.key to element)).toString()
}

private fun optionKey(field: SnapshotField, value: String): String {
    requireKey(value, "Value of field '${field.key.shown()}'")
    return value
}

private fun userId(field: SnapshotField, value: String): Long =
    positiveId(value) ?: throw BadRequestException("Value of field '${field.key.shown()}' must be a user id")

/**
 * `CASE status_key WHEN ? THEN 0 WHEN ? THEN 1 ... ELSE n END`: the status's position in the workflow, from the keys of
 * `RoomWorkflowService.snapshot` in position order (retired ones too, a thread can still sit in one). The ELSE keeps a key the snapshot
 * lacks last instead of failing the list.
 */
internal fun statusOrder(workflow: RoomWorkflowSnapshot): Expression<Int> {
    val keys = workflow.statuses.map { it.key }
    val statusKey = ThreadService.Threads.statusKey
    val order = Case().When(statusKey eq keys.first(), intLiteral(0))
    keys.drop(1).forEachIndexed { index, key -> order.When(statusKey eq key, intLiteral(index + 1)) }
    return order.Else(intLiteral(keys.size))
}
