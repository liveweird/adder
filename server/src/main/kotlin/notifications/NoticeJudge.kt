package ch.nokillswit.notifications

import ch.nokillswit.threads.Notice
import ch.nokillswit.threads.NoticeReason
import org.jetbrains.exposed.v1.core.ArrayColumnType
import org.jetbrains.exposed.v1.core.BooleanColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import kotlinx.coroutines.flow.toList

/** One person the writer will insert a row for: the kind and the two channel verdicts. */
internal data class Delivery(val userId: UInt, val type: NotificationType, val inApp: Boolean, val email: Boolean)

/** The reason a notice names and the ones it falls back to, in priority order: every reason that applies to the person. */
internal fun Notice.allReasons(): List<NoticeReason> = listOf(reason) + fallbacks

/** The kinds addressed TO the person (the four mention kinds and a hand-over): what the rail's count and the `@` marks count. */
private val ADDRESSED_KINDS = setOf(
    NotificationType.MENTIONED,
    NotificationType.TEAM_MENTIONED,
    NotificationType.WATCHERS_MENTIONED,
    NotificationType.EVERYONE_MENTIONED,
    NotificationType.THREAD_ASSIGNED,
)

/**
 * How a notice reaches its person and how an edit re-judges the rows it already made, split out of [NoticeWriter] (the file-size cap):
 * the channel verdict ([judge]) and the in-place relabel of a message's notices ([relabel], with [heldRows]).
 */
internal class NoticeJudge(
    /** False when the deployment has no outbound email: nothing can be mailed, so email is never a reason to make a row. */
    private val mailAvailable: Boolean,
) {
    /**
     * How [notice] reaches its person: the FIRST of its reasons (the most specific, then the fallbacks in priority order) whose kind is
     * switched on for the app or for email, with the channels that are on; null when every applicable reason is off on both channels.
     * [emailReachable] is false when email cannot be the reason (the row was already mailed or settled).
     */
    fun judge(notice: Notice, preferences: Map<UInt, EffectivePreferences>, emailReachable: Boolean = true): Delivery? {
        val person = preferences[notice.userId] ?: EffectivePreferences.DEFAULTS
        for (reason in notice.allReasons()) {
            val type = reason.toNotificationType()
            val inApp = person.on(type, NotificationChannel.IN_APP)
            val email = mailAvailable && emailReachable && person.on(type, NotificationChannel.EMAIL)
            if (inApp || email) return Delivery(notice.userId, type, inApp, email)
        }
        return null
    }

    /** What [relabel] decided: the people whose row goes, and the people whose bell changes (a hint for them). */
    class Relabelled(val deleted: List<UInt>, val changedBell: List<UInt>, val count: Int = 0)

    /**
     * A relabelled notice keeps its row (and its seen state) under the new kind, but the person's channels are judged again for it, with
     * the usual fallback to their next applicable reason: a kind switched off in the app is no longer shown (`in_app` follows the
     * verdict), and a row that would be neither shown nor mailable is deleted. Email counts as reachable only while the row has not been
     * mailed or settled (`emailed_at IS NULL`): that stamp is never reopened, so a settled row cannot be revived by an edit. One read of
     * the rows, one update, the deletes joining the caller's.
     */
    suspend fun relabel(messageId: UInt, relabels: List<Notice>): Relabelled {
        if (relabels.isEmpty()) return Relabelled(emptyList(), emptyList())
        val transaction = TransactionManager.current()
        val held = heldRows(messageId, relabels.map { it.userId })
        val kinds = relabels.flatMap { it.allReasons() }.map { it.toNotificationType() }.distinct()
        val preferences = effectivePreferencesOf(relabels.map { it.userId }, kinds)
        val deleted = mutableListOf<UInt>()
        val changed = mutableListOf<UInt>()
        val updates = mutableListOf<Delivery>()
        for (notice in relabels) {
            val row = held[notice.userId] ?: continue
            val verdict = judge(notice, preferences, emailReachable = row.unmailed)
            if (verdict == null) {
                deleted += notice.userId
                if (row.inApp) changed += notice.userId
                continue
            }
            updates += verdict
            val addressedBefore = row.type?.let { it in ADDRESSED_KINDS } ?: false
            if (row.inApp != verdict.inApp || addressedBefore != (verdict.type in ADDRESSED_KINDS)) changed += notice.userId
        }
        if (updates.isNotEmpty()) {
            transaction.exec(
                RELABEL_ROWS,
                listOf(
                    ArrayColumnType<Long, List<Long>>(LongColumnType()) to updates.map { it.userId.toLong() },
                    ArrayColumnType<String, List<String>>(TextColumnType()) to updates.map { it.type.name },
                    ArrayColumnType<Boolean, List<Boolean>>(BooleanColumnType()) to updates.map { it.inApp },
                    IntegerColumnType() to messageId.toInt(),
                ),
            )
        }
        return Relabelled(deleted, changed.distinct(), updates.size)
    }

    /** The rows [users] hold about message [messageId]: kind, whether they show, whether their mail is still to come. */
    suspend fun heldRows(messageId: UInt, users: List<UInt>): Map<UInt, Held> = TransactionManager.current().exec(
        READ_RELABEL_ROWS,
        listOf(
            IntegerColumnType() to messageId.toInt(),
            ArrayColumnType<Long, List<Long>>(LongColumnType()) to users.map { it.toLong() },
        ),
        explicitStatementType = StatementType.SELECT,
    ) { row ->
        Held(
            row.get(0, Long::class.javaObjectType)!!.toUInt(),
            NotificationType.entries.firstOrNull { it.name == row.get(1, String::class.java) },
            row.get(2, Boolean::class.javaObjectType) == true,
            row.get(3, Boolean::class.javaObjectType) == true,
        )
    }?.toList().orEmpty().filterNotNull().associateBy { it.userId }

    class Held(val userId: UInt, val type: NotificationType?, val inApp: Boolean, val unmailed: Boolean)
}
