package ch.nokillswit.notifications

import ch.nokillswit.audit.audit
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.mail.Mailer
import ch.nokillswit.infra.time.MILLIS_PER_DAY
import ch.nokillswit.rooms.RoomSummary
import ch.nokillswit.threads.ThreadService
import ch.nokillswit.threads.userNames
import ch.nokillswit.users.UserService
import ch.nokillswit.plugins.scrubbedSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ArrayColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.update
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("ch.nokillswit.notifications.NotificationDigest")

/** Notices one run claims at most: the batch AND the run's cap (the rest wait for the next tick; a flood cannot make a run unbounded). */
internal const val DIGEST_BATCH = 500

/**
 * People remembered as "mailed within the interval". A run that finds the memory this full claims nothing and says so (fail closed: a
 * process that cannot remember whom it mailed must not mail again). Above what the default interval allows (15 ticks of at most
 * [DIGEST_BATCH] people is 7,500); a much longer interval at full batches can reach it, and the digest then pauses until entries expire.
 */
internal const val DIGEST_MAX_REMEMBERED = 50_000

/**
 * Failures in a row (a failed re-check or a failed send) that end a run: with SMTP or the database down every further attempt is a certain
 * failure, and a run that tried the whole batch would hold the sweep's tick (and the purge behind it) for as long as the timeouts add up.
 * The untried recipients' claims are released.
 */
internal const val DIGEST_MAX_CONSECUTIVE_FAILURES = 3

/**
 * The claim (`persistence.md` "Notices"): ONE statement, constant text. The candidates are picked in `created_at` order off
 * `idx_notifications_email_due` and locked `FOR UPDATE SKIP LOCKED` (a row another process has claimed, or is claiming, is skipped, never
 * waited for), then stamped `emailed_at` in the same statement: a notice is claimed, hence mailed, at most once. The candidates are the
 * unseen, unclaimed notices that are older than the delay, younger than a day (nothing older is ever mailed) and not of a person already
 * mailed within the interval (their notices stay unclaimed and ride one later email). `MATERIALIZED` keeps the pick to one evaluation, so
 * `LIMIT` is a real bound. Every parameter is cast.
 */
private const val CLAIM = """
WITH picked AS MATERIALIZED (
    SELECT c.id FROM notifications c
    WHERE c.seen_at IS NULL AND c.emailed_at IS NULL
      AND c.created_at <= CAST(? AS int8) AND c.created_at > CAST(? AS int8)
      AND c.recipient_id <> ALL(CAST(? AS int8[]))
    ORDER BY c.created_at LIMIT CAST(? AS int4) FOR UPDATE SKIP LOCKED)
UPDATE notifications n SET emailed_at = CAST(? AS int8)
FROM picked WHERE n.id = picked.id
RETURNING n.id, n.recipient_id, n.room_id, n.thread_id, n.message_id, n.notification_type, n.actor_id"""

/** One claimed notice: ids and its kind, nothing else (a notice has nothing else). */
internal data class ClaimedNotice(
    val id: UInt,
    val recipientId: UInt,
    val roomId: UInt,
    val threadId: UInt,
    val messageId: UInt?,
    val type: NotificationType,
    val actorId: UInt,
)

/** What one run did, in counts (a test seam and the log line). */
internal data class DigestRun(val claimed: Int, val sent: Int, val failed: Int, val released: Int = 0)

/**
 * The email digest of unseen notices (`chat-model.md` "Notifications (V18)", `security.md` "Outbound email"). A run (the sweep's tick):
 *
 * 1. **Claims**, one short transaction ([CLAIM]): the notices still unseen after [delayMillis], at most [DIGEST_BATCH], marked BEFORE any
 *    send, so a notice is mailed at most once and a failed send is not retried (it is audited).
 * 2. **Sends**, outside every transaction, one email per recipient. Right before each send the recipient is RE-CHECKED in one short
 *    read transaction: the account is active, the notice still exists unseen (a removed member's notices are deleted with the membership,
 *    a deleted message's with it), the room is an active room the person is an active member of, and the kind is not switched off. What
 *    fails the check is skipped silently and stays claimed (never mailed later). A pooled connection is never held across the send.
 * 3. **Audits** `notification.email_sent` (userId, count) or `notification.email_failed` (userId, errorType: the class name only). A
 *    failure of the re-check is audited the same way as a failure of the send: the recipient was attempted and is not retried.
 * 4. **Releases** the claims of recipients that were never ATTEMPTED when the run ends early (cancelled, or
 *    [DIGEST_MAX_CONSECUTIVE_FAILURES] failures in a row): `emailed_at` goes back to null in a `NonCancellable` finally, so a stop or an
 *    outage does not silently settle notices nobody tried to mail. That is not a retry: nothing was sent. What can still be lost is a
 *    recipient attempted when the process dies or is cancelled MID-SEND (the send may or may not have left): at most once is the rule.
 *
 * "Mailed within the interval" ([minIntervalMillis]) lives in memory, per process, like the login throttle: a restart forgets it (at worst
 * one early email), and a person inside it is left unclaimed. A null [mailer] (mail disabled) does nothing and says so once. The text is
 * [notificationEmail]'s: who, the room, the thread number and a link, never a title or a body.
 */
internal class NotificationDigest(
    private val database: R2dbcDatabase,
    /** The ACTIVE rooms a person belongs to ([ch.nokillswit.rooms.RoomService.memberRooms]); a function so a test can fail it. */
    private val memberRooms: suspend (UInt) -> List<RoomSummary>,
    private val mailer: Mailer?,
    private val appUrl: String?,
    private val delayMillis: Long,
    private val minIntervalMillis: Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val batchSize: Int = DIGEST_BATCH,
    /** A TEST seam: runs after a non-empty claim and before the first send (the gap the send-time re-check exists for). */
    private val afterClaim: suspend () -> Unit = {},
) {
    private val lock = Mutex()
    private val mailedAt = HashMap<UInt, Long>()
    private var disabledLogged = false

    /** One run. Answers its counts; an empty run is `DigestRun(0, 0, 0)`. */
    suspend fun run(): DigestRun {
        val mailer = this.mailer
        if (mailer == null) {
            if (!disabledLogged) {
                disabledLogged = true
                log.info("notification email digest is off: this deployment has no outbound email (mail.transport=disabled)")
            }
            return DigestRun(0, 0, 0)
        }
        return lock.withLock { runLocked(mailer) }
    }

    private suspend fun runLocked(mailer: Mailer): DigestRun {
        val now = clock()
        mailedAt.values.removeAll { now - it >= minIntervalMillis }
        if (mailedAt.size >= DIGEST_MAX_REMEMBERED) {
            log.warn("notification email digest paused: {} recipients are inside the mail interval", mailedAt.size)
            return DigestRun(0, 0, 0)
        }
        val claimed = claim(now)
        if (claimed.isEmpty()) return DigestRun(0, 0, 0)
        // The recipients not yet attempted, in claim order. One leaves the queue as its send starts (before that, a stop releases it).
        val queue = ArrayDeque(claimed.groupBy { it.recipientId }.entries.toList())
        var sent = 0
        var failed = 0
        var inARow = 0
        var released = 0
        try {
            afterClaim()
            while (queue.isNotEmpty() && inARow < DIGEST_MAX_CONSECUTIVE_FAILURES) {
                val (recipientId, notices) = queue.first()
                val leave = { if (queue.firstOrNull()?.key == recipientId) queue.removeFirst() else null }
                val outcome = deliver(mailer, recipientId, notices, now) { leave() }
                when (outcome) {
                    Delivery.SENT -> { sent++; inARow = 0 }
                    Delivery.FAILED -> { failed++; inARow++ }
                    Delivery.SKIPPED -> Unit
                }
            }
        } finally {
            if (queue.isNotEmpty()) released = release(queue.flatMap { it.value }.map { it.id }, now)
            log.info(
                "notification email digest: {} notice(s) claimed, {} email(s) sent, {} failed, {} released",
                claimed.size, sent, failed, released,
            )
        }
        return DigestRun(claimed.size, sent, failed, released)
    }

    private enum class Delivery { SENT, FAILED, SKIPPED }

    /**
     * Re-checks, then sends, one recipient. [attempted] runs right before the send (the recipient stops being releasable). A failure of
     * either step is audited with the class name only and is not retried; cancellation passes through.
     */
    private suspend fun deliver(
        mailer: Mailer,
        recipientId: UInt,
        notices: List<ClaimedNotice>,
        now: Long,
        attempted: () -> Unit,
    ): Delivery {
        try {
            val mail = resolve(recipientId, notices)
            attempted()
            if (mail == null) return Delivery.SKIPPED
            mailedAt[recipientId] = now
            mailer.send(mail.email, mail.subject, mail.body)
            audit("notification.email_sent", "userId" to recipientId.toLong(), "count" to mail.count)
            return Delivery.SENT
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            attempted() // a failed re-check or send is audited and not retried: the recipient leaves the queue
            // The class name only: a mail provider's exception may embed the message it was sending.
            audit("notification.email_failed", "userId" to recipientId.toLong(), "errorType" to e.javaClass.simpleName)
            log.warn("notification email failed ({})", e.javaClass.simpleName)
            return Delivery.FAILED
        }
    }

    /**
     * Gives back the claims of [ids] (stamped [stamp] by this run) in a `NonCancellable` context, so a cancelled run still releases.
     * Answers how many went back; a failure here is logged (scrubbed) and the rows stay settled (the cost is an unmailed digest).
     */
    private suspend fun release(ids: List<UInt>, stamp: Long): Int = withContext(NonCancellable) {
        try {
            suspendTransaction(database) {
                val n = NotificationService.Notifications
                n.update({ (n.id inList ids) and (n.emailedAt eq stamp) }) { it[emailedAt] = null }
            }
        } catch (e: Exception) {
            log.warn("notification email digest could not release {} claim(s): {}", ids.size, e.scrubbedSummary())
            0
        }
    }

    private suspend fun claim(now: Long): List<ClaimedNotice> = suspendTransaction(database) {
        val arguments = listOf(
            LongColumnType() to now - delayMillis,
            LongColumnType() to now - MILLIS_PER_DAY,
            ArrayColumnType<Long, List<Long>>(LongColumnType()) to mailedAt.keys.map { it.toLong() },
            IntegerColumnType() to batchSize,
            LongColumnType() to now,
        )
        TransactionManager.current().exec(CLAIM, arguments, explicitStatementType = StatementType.SELECT) { row ->
            val type = NotificationType.entries.firstOrNull { it.name == row.get(5, String::class.java) }
            type?.let {
                ClaimedNotice(
                    id = row.get(0, Int::class.javaObjectType)!!.toUInt(),
                    recipientId = row.get(1, Long::class.javaObjectType)!!.toUInt(),
                    roomId = row.get(2, Int::class.javaObjectType)!!.toUInt(),
                    threadId = row.get(3, Int::class.javaObjectType)!!.toUInt(),
                    messageId = row.get(4, Int::class.javaObjectType)?.toUInt(),
                    type = it,
                    actorId = row.get(6, Long::class.javaObjectType)!!.toUInt(),
                )
            }
        }?.toList().orEmpty().filterNotNull().sortedBy { it.id }
    }

    /**
     * The send-time re-check and the current names, in ONE read transaction; null when nothing is left to mail. The names are read now
     * (a rename shows), for the entries that survive only.
     */
    private suspend fun resolve(recipientId: UInt, notices: List<ClaimedNotice>): Mail? = suspendTransaction(database) {
        val users = UserService.Users
        val user = users.select(users.name, users.email, users.language)
            .where { (users.id eq recipientId) and users.active() }.toList().singleOrNull()
            ?: return@suspendTransaction null
        val n = NotificationService.Notifications
        val pending = n.select(n.id).where { (n.id inList notices.map { it.id }) and n.seenAt.isNull() }
            .map { it[n.id].value }.toList().toSet()
        val roomNames = memberRooms(recipientId).associate { it.id to it.name }
        val off = emailDisabledKinds(recipientId)
        val kept = notices.filter { it.id in pending && it.roomId in roomNames && it.type !in off }
        if (kept.isEmpty()) return@suspendTransaction null
        val threads = ThreadService.Threads
        val numbers = threads.select(threads.id, threads.number).where { threads.id inList kept.map { it.threadId }.toSet() }
            .map { it[threads.id].value to it[threads.number] }.toList().toMap()
        val actors = userNames(kept.map { it.actorId })
        val entries = kept.mapNotNull { notice ->
            val number = numbers[notice.threadId] ?: return@mapNotNull null
            val room = roomNames.getValue(notice.roomId)
            DigestEntry(notice.type, actors[notice.actorId].orEmpty(), notice.roomId, room, number, notice.messageId)
        }
        if (entries.isEmpty()) return@suspendTransaction null
        val email = notificationEmail(user[users.name], entries, appUrl, user[users.language])
        Mail(user[users.email], email.subject, email.body, entries.size)
    }

    /** What one person is mailed, resolved at send time. */
    private class Mail(val email: String, val subject: String, val body: String, val count: Int)
}
