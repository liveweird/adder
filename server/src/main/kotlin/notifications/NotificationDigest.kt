package ch.nokillswit.notifications

import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.mail.Mailer
import ch.nokillswit.rooms.RoomSummary
import ch.nokillswit.plugins.scrubbedSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("ch.nokillswit.notifications.NotificationDigest")

/** PEOPLE one run claims at most (the rest wait): oldest waiting first per recipient, so one flooded backlog crowds out nobody. */
internal const val DIGEST_BATCH = 500

/** Notices of ONE person a mail reads as entries (it lists [DIGEST_MAX_ENTRIES]); the re-check's survivors beyond are only counted. */
internal const val DIGEST_MAX_NOTICES_PER_RECIPIENT = 50

/**
 * Rows ONE scan of the due window reads at most: the claim's `eligible` set and the settle's scan are each `ORDER BY created_at LIMIT`
 * this (oldest first, off `idx_notifications_email_due`). It bounds the work a tick does however many notices are due, and it bounds
 * the backlog one person can be stamped for in a single claim. **The trade-off is fairness**: a person whose notices all lie beyond the
 * oldest [DIGEST_SCAN_CAP] due ones waits for a later tick (the head of the window leaves the index as it is mailed or settled, so the
 * wait is bounded by how fast the head drains). Rows the claim's filters reject (a person inside the interval, a kind off for email) are
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
 * Failures in a row (a failed re-check or send) that end a run: with SMTP or the database down every further attempt is a certain failure
 * and would hold the sweep's tick (and the purge behind it) for as long as the timeouts add up. The untried claims are released.
 */
internal const val DIGEST_MAX_CONSECUTIVE_FAILURES = 3

/** The bounds of the claim's statements: rows one scan reads ([DIGEST_SCAN_CAP]) and the statement budget in seconds. */
internal data class DigestBounds(
    val scanCap: Int = DIGEST_SCAN_CAP,
    val statementTimeoutSeconds: Int = DIGEST_STATEMENT_TIMEOUT_SECONDS,
    /** Fixed production policy; this override lives on the existing test seam rather than widening the digest constructor. */
    val actorRecipientIntervalMillis: Long = ACTOR_RECIPIENT_INTERVAL_MILLIS,
)

internal data class ClaimedNotice(
    val id: UInt,
    val recipientId: UInt,
    val roomId: UInt,
    val threadId: UInt?,
    val messageId: UInt?,
    val type: NotificationType,
    val actorId: UInt,
    /** 1-based rank among the person's notices of this claim, oldest first. */
    val rank: Long,
)

internal data class GroupedDigestNotice(val notice: ClaimedNotice, val replyCount: Int)

/** Collapses only plain reply notices, at their oldest occurrence, while every individually addressed kind remains its own line. */
internal fun groupDigestNotices(notices: List<ClaimedNotice>): List<GroupedDigestNotice> {
    val grouped = mutableListOf<GroupedDigestNotice>()
    val replyIndexes = mutableMapOf<Pair<UInt, UInt?>, Int>()
    for (notice in notices.sortedBy { it.rank }) {
        if (notice.type != NotificationType.THREAD_REPLIED) {
            grouped += GroupedDigestNotice(notice, 1)
            continue
        }
        val key = notice.roomId to notice.threadId
        val index = replyIndexes[key]
        if (index == null) {
            replyIndexes[key] = grouped.size
            grouped += GroupedDigestNotice(notice, 1)
        } else {
            val current = grouped[index]
            // Keep the oldest message target/rank. Grouped reply wording is actor-free, so no arbitrary actor is exposed.
            grouped[index] = current.copy(replyCount = current.replyCount + 1)
        }
    }
    return grouped
}

/** What one run did, in counts (a test seam and the log line). */
internal data class DigestRun(val claimed: Int, val sent: Int, val failed: Int, val released: Int = 0)

/**
 * The email digest of unseen notices (`chat-model.md` "Notifications (V18)", `security.md` "Outbound email"). A run (the sweep's tick):
 *
 * 1. **Settles and claims**, one short transaction under a [DIGEST_STATEMENT_TIMEOUT_SECONDS] statement budget ([SETTLE], then
 *    [CLAIM]; both read at most `scanCap` rows): notices of a kind that is off for email or a gone account are settled; then the
 *    notices still unseen after [delayMillis] of at most [peopleToClaim] PEOPLE (oldest waiting first; ALL of a person's due
 * notices within the scan,
 *    the first [DIGEST_MAX_NOTICES_PER_RECIPIENT] listed and the rest counted, so a flooded person gets one mail, not one a tick) are
 *    marked BEFORE any send, so a notice is mailed at most once and a failed send is not retried (it is audited). A statement past its
 *    budget is logged scrubbed and the run ends with nothing claimed (the transaction rolled back).
 * 2. **Sends**, outside every transaction, one email per recipient, at most [maxPerRun] mails a run (the budget the MFA and password-reset
 *    mails are protected by: they share the `Mailer`; the claims beyond it are released). Right before each send ALL of the recipient's
 *    claimed notices are RE-CHECKED in one short read transaction: the account is active, the notice still exists unseen (a removed
 *    member's notices go with the membership, a deleted message's with it), the room is an active room the person is a member of, the
 *    kind's email switch is on and the thread exists. The survivors are listed (the first [DIGEST_MAX_NOTICES_PER_RECIPIENT]) and
 *    counted (the rest). With none the recipient is skipped silently, stays claimed (never mailed later) and claims no slot at the next
 *    tick. No pooled connection is held across the send.
 *    After that final check, the eligible `(actor, recipient)` pairs are reserved in their own ordered transaction. A pair attempted
 *    within the preceding hour is removed from this digest and its notice claims are released; unrelated actors can still be mailed.
 *    The reservation stays for a failed or cancellation-unknown SMTP attempt, so restarts and races cannot bypass the pacing rule.
 * 3. **Audits** `notification.email_sent` (userId, count) or `notification.email_failed` (userId, errorType: the class name only), for a
 *    failed re-check too: the recipient was attempted and is not retried.
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
    private val clock: () -> Long = ::nowMillis,
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
    private val store = NotificationDigestStore(database, delayMillis, bounds)
    private val actorRecipientIntervalMillis = bounds.actorRecipientIntervalMillis

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
        val claim = try {
            store.claim(now, mailedAt.keys, batchSize, maxPerRun, actorRecipientIntervalMillis)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A statement past its budget (SQLSTATE 57014) or a failing database: the transaction rolled back, so nothing is claimed.
            log.warn("notification email digest could not claim: {}", e.scrubbedSummary())
            return DigestRun(0, 0, 0)
        }
        if (claim.settled > 0) {
            log.info("notification email digest: {} notice(s) of a kind off for email or a gone account settled", claim.settled)
        }
        val claimed = claim.notices
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
                val result = delivery(mailer).deliver(recipientId, notices, now) { attemptedAt ->
                    leave()
                    mailedAt[recipientId] = attemptedAt
                }
                released += result.released
                when (result.status) {
                    DigestDeliveryStatus.SENT -> { sent++; inARow = 0 }
                    DigestDeliveryStatus.FAILED -> { failed++; inARow++ }
                    DigestDeliveryStatus.SKIPPED -> Unit
                    DigestDeliveryStatus.PACED -> leave()
                }
            }
        } finally {
            if (queue.isNotEmpty()) released += releaseSafely(queue.flatMap { it.value }.map { it.id }, now)
            log.info(
                "notification email digest: {} notice(s) claimed, {} email(s) sent, {} failed, {} released",
                claimed.size, sent, failed, released,
            )
        }
        return DigestRun(claimed.size, sent, failed, released)
    }

    private fun delivery(mailer: Mailer) = NotificationDigestDelivery(
        database,
        memberRooms,
        mailer,
        appUrl,
        store,
        actorRecipientIntervalMillis,
        clock,
        log,
    )

    /**
     * Gives back the claims of [ids] (stamped [stamp] by this run) in a `NonCancellable` context, so a cancelled run still releases.
     * Answers how many went back; a failure here is logged (scrubbed) and the rows stay settled (the cost is an unmailed digest).
     */
    private suspend fun releaseSafely(ids: List<UInt>, stamp: Long): Int = withContext(NonCancellable) {
        try {
            store.release(ids, stamp)
        } catch (e: Exception) {
            log.warn("notification email digest could not release {} claim(s): {}", ids.size, e.scrubbedSummary())
            0
        }
    }

    /**
     * Sweep integration seam. Expiry cleanup is isolated from both notice retention and pair reservation: a failure is scrubbed and
     * reported as null, while cancellation still stops the sweep. The caller may invoke it on the hourly retention pass.
     */
    suspend fun purgeExpiredPacing(batchSize: Int = PACING_PURGE_BATCH): Int =
        store.purgeExpiredPacing(clock() - actorRecipientIntervalMillis, batchSize)
}
