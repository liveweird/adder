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

/**
 * PEOPLE one run claims at most (the rest wait for the next tick): the claim picks per recipient, oldest waiting first, so one flooded
 * person's backlog cannot crowd out everybody else.
 */
internal const val DIGEST_BATCH = 500

/** Notices of ONE person a claim takes at most (a digest lists [DIGEST_MAX_ENTRIES]); a longer backlog rides the next digest. */
internal const val DIGEST_MAX_NOTICES_PER_RECIPIENT = 50

/**
 * Rows ONE scan of the due window reads at most: the claim's `eligible` set and the settle's scan are each `ORDER BY created_at LIMIT`
 * this (oldest first, off `idx_notifications_email_due`). It bounds the work a tick does however many notices are due, and it bounds
 * the backlog one person can be stamped for in a single claim. **The trade-off is fairness**: a person whose notices all lie beyond the
 * oldest [DIGEST_SCAN_CAP] due ones waits for a later tick (the head of the window leaves the index as it is mailed or settled, so the
 * wait is bounded by how fast the head drains). Rows the claim's filters reject (a person inside the interval, a kind switched off) are
 * still walked to find those rows; the settle removes the second kind and [DIGEST_STATEMENT_TIMEOUT_SECONDS] bounds the rest.
 */
internal const val DIGEST_SCAN_CAP = 5_000

/** The claim's and the settle's statement budget, whole seconds (the transaction's `queryTimeout`, the only way a timeout is set). */
internal const val DIGEST_STATEMENT_TIMEOUT_SECONDS = 10

/** People a claim takes beyond the run's send budget (at least this, or a fifth of it): the margin for those skipped at send time. */
internal const val DIGEST_MIN_SLACK = 10

/**
 * People one claim takes: the send budget plus a margin (people skipped at send time do not use a send), never more than [batchSize].
 * A claim of 5x what a run may send would stamp the rest only to release it again.
 */
internal fun peopleToClaim(batchSize: Int, maxPerRun: Int): Int = minOf(batchSize, maxPerRun + maxOf(DIGEST_MIN_SLACK, maxPerRun / 5))

/** Mails one run sends at most when `notifications.email.maxPerRun` is not given (`security.md`: the digest shares the `Mailer`). */
internal const val DIGEST_MAX_PER_RUN = 100

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
 * The notices a digest may mail, one fragment for the claim (alias `c`, three parameters: the delay cutoff, the day floor, the people
 * inside the interval): unseen, unclaimed, older than the delay, younger than a day (nothing older is ever mailed), of an ACTIVE
 * account, of a kind the person has NOT switched off for email, and not of a person already mailed within the interval (their notices
 * stay unclaimed and ride one later email).
 */
private const val DUE = """c.seen_at IS NULL AND c.emailed_at IS NULL
      AND c.created_at <= CAST(? AS int8) AND c.created_at > CAST(? AS int8)
      AND c.recipient_id <> ALL(CAST(? AS int8[]))
      AND EXISTS (SELECT 1 FROM users u WHERE u.id = c.recipient_id AND u.marked_as_deleted = false)
      AND NOT EXISTS (SELECT 1 FROM user_notification_preferences p
                      WHERE p.user_id = c.recipient_id AND p.channel = 'EMAIL' AND p.notification_type = c.notification_type)"""

/**
 * The claim (`persistence.md` "Notices"): ONE statement, constant text. The [DUE] notices, the oldest `?` of them ([DIGEST_SCAN_CAP]),
 * are grouped by person and the people taken in the order of their OLDEST waiting notice (at most `?` people: a flooded backlog is one
 * person's slot, never everybody's); ALL of a taken person's notices in the scan are locked `FOR UPDATE SKIP LOCKED` (a row another
 * process has claimed, or is claiming, is skipped, never waited for) and stamped `emailed_at` in the same statement, so a flooded
 * person's backlog is one digest and not a mail per tick: a notice is claimed, hence mailed, at most once. Each row comes back with its
 * rank among the person's notices, oldest first (the digest lists the first [DIGEST_MAX_NOTICES_PER_RECIPIENT] and counts the rest).
 * `MATERIALIZED` keeps each step to one evaluation. The scan is bounded by the cap, not by the people limit. Every parameter is cast.
 */
private const val CLAIM = """
WITH eligible AS MATERIALIZED (
    SELECT c.id, c.recipient_id, c.created_at FROM notifications c WHERE $DUE
    ORDER BY c.created_at, c.id LIMIT CAST(? AS int4)),
people AS MATERIALIZED (
    SELECT recipient_id, MIN(created_at) AS oldest FROM eligible
    GROUP BY recipient_id ORDER BY oldest, recipient_id LIMIT CAST(? AS int4)),
ranked AS MATERIALIZED (
    SELECT e.id, row_number() OVER (PARTITION BY e.recipient_id ORDER BY e.created_at, e.id) AS rn
    FROM eligible e JOIN people ON people.recipient_id = e.recipient_id),
picked AS MATERIALIZED (
    SELECT n.id, r.rn FROM notifications n JOIN ranked r ON r.id = n.id
    WHERE n.seen_at IS NULL AND n.emailed_at IS NULL
    ORDER BY n.id FOR UPDATE OF n SKIP LOCKED)
UPDATE notifications n SET emailed_at = CAST(? AS int8)
FROM picked WHERE n.id = picked.id
RETURNING n.id, n.recipient_id, n.room_id, n.thread_id, n.message_id, n.notification_type, n.actor_id, picked.rn"""

/**
 * The settle, in the claim's transaction, before the claim: of the oldest `?` due notices ([DIGEST_SCAN_CAP], read once, so the
 * statement never walks more), the ones a digest can never mail (the kind switched off for email, or an account that is gone) are
 * stamped `emailed_at`, so they leave `idx_notifications_email_due` for good and never fill a scan again. A person who switches the
 * kind back on gets the notices made from then on. A never-mailable notice deeper than the cap waits until the head of the window has
 * drained.
 */
private const val SETTLE = """
WITH scanned AS MATERIALIZED (
    SELECT x.id, x.recipient_id, x.notification_type FROM notifications x
    WHERE x.seen_at IS NULL AND x.emailed_at IS NULL AND x.created_at <= CAST(? AS int8) AND x.created_at > CAST(? AS int8)
    ORDER BY x.created_at, x.id LIMIT CAST(? AS int4)),
doomed AS MATERIALIZED (
    SELECT n.id FROM notifications n
    WHERE n.id IN (
        SELECT c.id FROM scanned c
        WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.id = c.recipient_id AND u.marked_as_deleted = false)
           OR EXISTS (SELECT 1 FROM user_notification_preferences p
                      WHERE p.user_id = c.recipient_id AND p.channel = 'EMAIL' AND p.notification_type = c.notification_type))
      AND n.seen_at IS NULL AND n.emailed_at IS NULL
    ORDER BY n.id FOR UPDATE SKIP LOCKED)
UPDATE notifications n SET emailed_at = CAST(? AS int8)
FROM doomed WHERE n.id = doomed.id
RETURNING n.id"""

/** The bounds of the claim's statements: rows one scan reads ([DIGEST_SCAN_CAP]) and the statement budget in seconds. */
internal data class DigestBounds(
    val scanCap: Int = DIGEST_SCAN_CAP,
    val statementTimeoutSeconds: Int = DIGEST_STATEMENT_TIMEOUT_SECONDS,
)

/** One claimed notice: ids and its kind, nothing else (a notice has nothing else). */
internal data class ClaimedNotice(
    val id: UInt,
    val recipientId: UInt,
    val roomId: UInt,
    val threadId: UInt,
    val messageId: UInt?,
    val type: NotificationType,
    val actorId: UInt,
    /** 1-based rank among the person's notices of this claim, oldest first. */
    val rank: Long,
)

/** What one run did, in counts (a test seam and the log line). */
internal data class DigestRun(val claimed: Int, val sent: Int, val failed: Int, val released: Int = 0)

/**
 * The email digest of unseen notices (`chat-model.md` "Notifications (V18)", `security.md` "Outbound email"). A run (the sweep's tick):
 *
 * 1. **Settles and claims**, one short transaction under a [DIGEST_STATEMENT_TIMEOUT_SECONDS] statement budget ([SETTLE], then
 *    [CLAIM]; both read at most `scanCap` rows): notices of a switched-off kind or a gone account are settled; then the notices still
 *    unseen after [delayMillis] of at most [peopleToClaim] PEOPLE (oldest waiting first; ALL of a person's due notices within the scan,
 *    the first [DIGEST_MAX_NOTICES_PER_RECIPIENT] listed and the rest counted, so a flooded person gets one mail, not one a tick) are
 *    marked BEFORE any send, so a notice is mailed at most once and a failed send is not retried (it is audited). A statement past its
 *    budget is logged scrubbed and the run ends with nothing claimed (the transaction rolled back).
 * 2. **Sends**, outside every transaction, one email per recipient, at most [maxPerRun] mails a run (the budget the MFA and password-reset
 *    mails are protected by: they share the `Mailer`; the claims beyond it are released). Right before each send the recipient is
 *    RE-CHECKED in one short
 *    read transaction: the account is active, the notice still exists unseen (a removed member's notices are deleted with the membership,
 *    a deleted message's with it), the room is an active room the person is an active member of, and the kind is not switched off. What
 *    fails the check is skipped silently and stays claimed (never mailed later); the person is remembered like a mailed one, so a
 *    skipped recipient does not claim a slot again at the next tick. A pooled connection is never held across the send.
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
    /** The most people per claim ([DIGEST_BATCH]); the claim takes [peopleToClaim] of them. */
    private val batchSize: Int = DIGEST_BATCH,
    /** Mails per run ([DIGEST_MAX_PER_RUN]; `notifications.email.maxPerRun`). */
    private val maxPerRun: Int = DIGEST_MAX_PER_RUN,
    /** The claim's bounds ([DigestBounds]); a TEST seam. */
    private val bounds: DigestBounds = DigestBounds(),
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
        val claimed = try {
            claim(now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A statement past its budget (SQLSTATE 57014) or a failing database: the transaction rolled back, so nothing is claimed.
            log.warn("notification email digest could not claim: {}", e.scrubbedSummary())
            return DigestRun(0, 0, 0)
        }
        if (claimed.isEmpty()) return DigestRun(0, 0, 0)
        // The recipients not yet attempted, in claim order. One leaves the queue as its send starts (before that, a stop releases it).
        val queue = ArrayDeque(claimed.groupBy { it.recipientId }.entries.toList())
        var sent = 0
        var failed = 0
        var inARow = 0
        var released = 0
        try {
            afterClaim()
            while (queue.isNotEmpty() && inARow < DIGEST_MAX_CONSECUTIVE_FAILURES && sent + failed < maxPerRun) {
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
            val listed = notices.filter { it.rank <= DIGEST_MAX_NOTICES_PER_RECIPIENT }.sortedBy { it.rank }
            val mail = resolve(recipientId, listed, unlisted = notices.size - listed.size)
            attempted()
            mailedAt[recipientId] = now // a skipped person too: nothing to mail now, no slot in the next claim for one interval
            if (mail == null) return Delivery.SKIPPED
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
                queryTimeout = DIGEST_STATEMENT_TIMEOUT_SECONDS
                n.update({ (n.id inList ids) and (n.emailedAt eq stamp) }) { it[emailedAt] = null }
            }
        } catch (e: Exception) {
            log.warn("notification email digest could not release {} claim(s): {}", ids.size, e.scrubbedSummary())
            0
        }
    }

    private suspend fun claim(now: Long): List<ClaimedNotice> = suspendTransaction(database) {
        queryTimeout = bounds.statementTimeoutSeconds
        val cutoff = LongColumnType() to now - delayMillis
        val floor = LongColumnType() to now - MILLIS_PER_DAY
        val settled = TransactionManager.current().exec(
            SETTLE,
            listOf(cutoff, floor, IntegerColumnType() to bounds.scanCap, LongColumnType() to now),
            explicitStatementType = StatementType.SELECT,
        ) { row -> row.get(0, Int::class.javaObjectType) }?.toList().orEmpty().size
        if (settled > 0) log.info("notification email digest: {} notice(s) of a switched-off kind or a gone account settled", settled)
        val arguments = listOf(
            cutoff,
            floor,
            ArrayColumnType<Long, List<Long>>(LongColumnType()) to mailedAt.keys.map { it.toLong() },
            IntegerColumnType() to bounds.scanCap,
            IntegerColumnType() to peopleToClaim(batchSize, maxPerRun),
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
                    rank = row.get(7, Long::class.javaObjectType)!!,
                )
            }
        }?.toList().orEmpty().filterNotNull().sortedBy { it.id }
    }

    /**
     * The send-time re-check and the current names, in ONE read transaction; null when nothing is left to mail. The names are read now
     * (a rename shows), for the entries that survive only.
     */
    private suspend fun resolve(recipientId: UInt, notices: List<ClaimedNotice>, unlisted: Int): Mail? = suspendTransaction(database) {
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
        val email = notificationEmail(user[users.name], entries, appUrl, user[users.language], unlisted)
        Mail(user[users.email], email.subject, email.body, entries.size + unlisted)
    }

    /** What one person is mailed, resolved at send time. */
    private class Mail(val email: String, val subject: String, val body: String, val count: Int)
}
