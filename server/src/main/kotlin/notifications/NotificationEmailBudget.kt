package ch.nokillswit.notifications

import ch.nokillswit.audit.audit
import ch.nokillswit.infra.time.MILLIS_PER_DAY
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ArrayColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager

/** The default of `notifications.email.maxRecipientsPerActorPerDay`. */
internal const val DEFAULT_EMAIL_BUDGET_PER_ACTOR = 20

/** The ceiling of `notifications.email.maxRecipientsPerActorPerDay`: the admitted-people array of one actor-day is at most this long. */
internal const val MAX_EMAIL_BUDGET_PER_ACTOR = 1000

/** Makes sure the actor's row of the day exists (constant text, two casts); a no-op once it does. */
private const val ENSURE_ROW = """
INSERT INTO notification_email_budget (actor_id, day) VALUES (CAST(? AS int8), CAST(? AS int4))
ON CONFLICT (actor_id, day) DO NOTHING"""

/**
 * Reads and LOCKS the actor's row of the day: the people already admitted (comma separated, empty for none) and whether the budget
 * was already exceeded today. The lock lasts to the end of the caller's transaction, so two writes of one actor serialize here.
 */
private const val LOCK_ROW = """
SELECT COALESCE(array_to_string(recipients, ','), ''), exceeded_at IS NOT NULL FROM notification_email_budget
WHERE actor_id = CAST(? AS int8) AND day = CAST(? AS int4) FOR UPDATE"""

private const val SAVE_ROW = """
UPDATE notification_email_budget SET recipients = CAST(? AS int8[]), exceeded_at = COALESCE(exceeded_at, CAST(? AS int8))
WHERE actor_id = CAST(? AS int8) AND day = CAST(? AS int4)"""

/**
 * The per-actor digest budget (V24, `chat-model.md` "The digest budget", `security.md` "Notices"): the most DISTINCT people one actor's
 * notices can make eligible for the email digest in a UTC day, [perDay] of them, whatever the kind. Only the people whose email is on for
 * the notice are counted. Over the budget only the email is withheld (the writer stamps `emailed_at`, V22's "settled, never mailed"): the
 * in-app notice is still made when the person's in-app switch is on, and a person the app switch also leaves out gets no row at all.
 *
 * The first [perDay] distinct recipients of the day are admitted, in the order they were reached, and stay admitted for the rest of the
 * day (the same person counts once, however many notices they get); every later person's email that day is withheld. An ADMIN actor
 * is NOT exempt: a room owner and an ADMIN can enrol anyone, and the digest names the room the actor chose either way. The day is the
 * epoch day of [clock] (UTC), so the budget resets at 00:00 UTC.
 *
 * Runs INSIDE the write's transaction (under the room lock); the actor's row is locked `FOR UPDATE` until it commits, so the budget
 * holds when one actor writes in several rooms at once. That wait is deliberately NOT bounded by a `lock_timeout` (`persistence.md`
 * "The room lock"): its worst case is the same actor's in-flight notice-making writes, each a few milliseconds, and a timeout would
 * turn a harmless queue into a failed post. Not tied to the notices: removing a member deletes their notices, never their
 * place in the budget, so adding and removing people cannot reset it.
 */
internal class NotificationEmailBudget(
    private val perDay: Int,
    private val clock: () -> Long,
) {
    init {
        require(perDay in 1..MAX_EMAIL_BUDGET_PER_ACTOR) { "the digest budget must be 1..$MAX_EMAIL_BUDGET_PER_ACTOR (was $perDay)" }
    }

    /**
     * Of [eligible], the people [actorId] is about to notify in [roomId] whose email is on for the notice (in the order the caller listed
     * them, the most specific reason first; this runs BEFORE the insert), answers those over the budget, whose email is withheld.
     * Audits `notification.email_budget_exceeded` the first time the actor goes over today (coalesced by the committed `exceeded_at`; a
     * rolled-back write repeats it, which is harmless). Ids and counts only.
     */
    suspend fun withheld(actorId: UInt, roomId: UInt, eligible: List<UInt>): Set<UInt> {
        if (eligible.isEmpty()) return emptySet()
        val transaction = TransactionManager.current()
        val now = clock()
        val actor = LongColumnType() to actorId.toLong()
        val day = IntegerColumnType() to (now / MILLIS_PER_DAY).toInt()
        transaction.exec(ENSURE_ROW, listOf(actor, day))
        val (admittedText, alreadyExceeded) = transaction.exec(
            LOCK_ROW,
            listOf(actor, day),
            explicitStatementType = StatementType.SELECT,
        ) { row -> row.get(0, String::class.java)!! to (row.get(1, Boolean::class.javaObjectType) == true) }
            ?.toList().orEmpty().filterNotNull().single()
        val admitted = admittedText.split(',').filter { it.isNotEmpty() }.map { it.toUInt() }
        val fresh = eligible.distinct().filter { it !in admitted }
        val room = (perDay - admitted.size).coerceAtLeast(0)
        val newlyAdmitted = fresh.take(room)
        val over = fresh.drop(room).toSet()
        if (newlyAdmitted.isNotEmpty() || (over.isNotEmpty() && !alreadyExceeded)) {
            transaction.exec(
                SAVE_ROW,
                listOf(
                    ArrayColumnType<Long, List<Long>>(LongColumnType()) to (admitted + newlyAdmitted).map { it.toLong() },
                    LongColumnType() to if (over.isEmpty()) null else now,
                    actor,
                    day,
                ),
            )
        }
        if (over.isNotEmpty() && !alreadyExceeded) {
            audit(
                "notification.email_budget_exceeded",
                "actorId" to actorId.toLong(),
                "roomId" to roomId.toLong(),
                "budget" to perDay,
                "withheld" to over.size,
            )
        }
        return over
    }

    /** The retention of the budget rows: deletes the days before the one [cutoffMillis] falls on. Answers how many rows went. */
    suspend fun purgeBefore(cutoffMillis: Long): Int {
        val cutoffDay = (cutoffMillis / MILLIS_PER_DAY).toInt()
        return BudgetDays.deleteWhere { BudgetDays.day less cutoffDay }
    }

    /** Only the day column, for the retention delete (its row count is the answer); every other statement is the constants above. */
    private object BudgetDays : Table("notification_email_budget") {
        val day = integer("day")
    }
}
