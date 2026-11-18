package ch.nokillswit.rooms

import ch.nokillswit.workflows.FieldShape
import ch.nokillswit.workflows.FieldType
import ch.nokillswit.workflows.StatusCategory
import ch.nokillswit.workflows.StatusShape
import ch.nokillswit.workflows.shown
import io.ktor.server.plugins.BadRequestException

/**
 * The pure half of the room workflow PUT (`chat-model.md` "Locking and the room write", steps 4-7): given the room's STORED rows (live
 * and retired, read under the lock) and the incoming document, it works out what the change does - which keys are added, revived,
 * retired, re-categorised or re-typed - and judges the replacement mapping. No database, no request: the service feeds it rows and
 * thread counts and acts on the verdicts. The structural rules of the document itself are the kernel's (`validateWorkflowShape`).
 */
data class StoredStatus(val key: String, val category: StatusCategory, val retired: Boolean)

data class StoredField(val key: String, val type: FieldType, val retired: Boolean)

class RoomWorkflowPlan internal constructor(
    /** Keys the room never had: new rows. */
    val statusesAdded: List<String>,
    /** Keys of retired rows the document names again: the row comes back (a retired status has no threads, so it may change category). */
    val statusesRevived: List<String>,
    /** Live keys the document no longer names: they retire. */
    val statusesRetired: List<String>,
    /** Live keys that stay but change category: only legal while no thread uses them. */
    val statusesRecategorised: List<String>,
    val fieldsAdded: List<String>,
    val fieldsRevived: List<String>,
    val fieldsRetired: List<String>,
    /** Keys (live OR retired) whose stored type differs from the document's: a field's type is immutable per key. */
    val fieldsRetyped: List<String>,
    private val retiredCategories: Map<String, StatusCategory>,
    private val incomingCategories: Map<String, StatusCategory>,
) {
    /** What the audit counts as statuses added: a key that becomes live (new or revived). */
    val statusesBecomingLive: Int get() = statusesAdded.size + statusesRevived.size

    val fieldsBecomingLive: Int get() = fieldsAdded.size + fieldsRevived.size

    /** The statuses whose thread usage decides the change: the ones that retire and the ones that change category. */
    val statusesToCount: List<String> get() = statusesRetired + statusesRecategorised

    /**
     * The mapping's own rules, each a `400` (they need no thread count): every key must be a status THIS change retires, and its target
     * must be a status of the new document in the SAME category as the retired one (a workflow edit never closes or reopens a thread
     * behind its history). Echoed keys are bounded by `shown()`.
     */
    fun requireValidReplacements(replacements: Map<String, String>) {
        for ((from, to) in replacements) {
            val category = retiredCategories[from]
                ?: throw BadRequestException("Replacement key '${from.shown()}' is not a status this change retires")
            val target = incomingCategories[to]
                ?: throw BadRequestException("Replacement '${to.shown()}' for '$from' is not a status of the new workflow")
            if (target != category) {
                throw BadRequestException("Replacement '$to' for '$from' must be in the same category ($category), not $target")
            }
        }
    }

    /**
     * The statuses that cannot go through: those of [statusesToCount] with threads ([counts]) that are either re-categorised (no
     * mapping can help) or retired without an entry in [replacements]. Empty = the change is allowed.
     */
    fun statusesInUse(counts: Map<String, Long>, replacements: Map<String, String>): List<String> =
        statusesToCount.filter { (counts[it] ?: 0L) > 0L && (it in statusesRecategorised || it !in replacements) }

    /** The `(from, to)` moves to perform: every retired status that has threads and a mapping, in the order retired. */
    fun movesFor(counts: Map<String, Long>, replacements: Map<String, String>): List<Pair<String, String>> =
        statusesRetired.filter { (counts[it] ?: 0L) > 0L }.mapNotNull { from -> replacements[from]?.let { from to it } }
}

/** Works out the change from the stored rows ([storedStatuses] and [storedFields], retired included) and the incoming document. */
fun planRoomWorkflow(
    storedStatuses: List<StoredStatus>,
    storedFields: List<StoredField>,
    statuses: List<StatusShape>,
    fields: List<FieldShape>,
): RoomWorkflowPlan {
    val statusByKey = storedStatuses.associateBy { it.key }
    val incomingStatusKeys = statuses.map { it.key }.toSet()
    val retired = storedStatuses.filter { !it.retired && it.key !in incomingStatusKeys }
    val fieldByKey = storedFields.associateBy { it.key }
    val incomingFieldKeys = fields.map { it.key }.toSet()
    return RoomWorkflowPlan(
        statusesAdded = statuses.map { it.key }.filter { it !in statusByKey },
        statusesRevived = statuses.map { it.key }.filter { statusByKey[it]?.retired == true },
        statusesRetired = retired.map { it.key },
        statusesRecategorised = statuses.filter { s -> statusByKey[s.key]?.let { !it.retired && it.category != s.category } == true }
            .map { it.key },
        fieldsAdded = fields.map { it.key }.filter { it !in fieldByKey },
        fieldsRevived = fields.map { it.key }.filter { fieldByKey[it]?.retired == true },
        fieldsRetired = storedFields.filter { !it.retired && it.key !in incomingFieldKeys }.map { it.key },
        fieldsRetyped = fields.filter { f -> fieldByKey[f.key]?.let { it.type != f.type } == true }.map { it.key },
        retiredCategories = retired.associate { it.key to it.category },
        incomingCategories = statuses.associate { it.key to it.category },
    )
}
