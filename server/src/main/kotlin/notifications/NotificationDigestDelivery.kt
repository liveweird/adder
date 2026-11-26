package ch.nokillswit.notifications

import ch.nokillswit.audit.audit
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.mail.Mailer
import ch.nokillswit.rooms.RoomSummary
import ch.nokillswit.threads.ThreadService
import ch.nokillswit.threads.threadNotMutedBy
import ch.nokillswit.threads.userNames
import ch.nokillswit.users.UserService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.slf4j.Logger

private const val RECHECK_CHUNK = 1_000

internal enum class DigestDeliveryStatus { SENT, FAILED, SKIPPED, PACED }
internal data class DigestDeliveryResult(val status: DigestDeliveryStatus, val released: Int = 0)

/** Final access/preference resolution, persisted pair reservation, rendering and SMTP. Scheduling stays in [NotificationDigest]. */
internal class NotificationDigestDelivery(
    private val database: R2dbcDatabase,
    private val memberRooms: suspend (UInt) -> List<RoomSummary>,
    private val mailer: Mailer,
    private val appUrl: String?,
    private val store: NotificationDigestStore,
    private val actorRecipientIntervalMillis: Long,
    private val clock: () -> Long,
    private val log: Logger,
) {
    /**
     * [attempted] removes this recipient from the scheduler's releasable queue immediately before SMTP. Once pair reservation commits,
     * cancellation is an outcome-unknown attempt: admitted claims stay settled and the pacing rows stay; blocked claims are released
     * in [NonCancellable] context in ascending notification-id order.
     */
    suspend fun deliver(
        recipientId: UInt,
        notices: List<ClaimedNotice>,
        claimStamp: Long,
        attempted: (Long) -> Unit,
    ): DigestDeliveryResult {
        var blocked = emptyList<UInt>()
        var reserved = false
        var released = 0
        var attemptAt: Long? = null
        var markedAttempted = false
        fun markAttempted() {
            if (!markedAttempted) {
                attempted(attemptAt ?: clock())
                markedAttempted = true
            }
        }
        try {
            val candidate = resolve(recipientId, notices)
            if (candidate == null) {
                markAttempted()
                return DigestDeliveryResult(DigestDeliveryStatus.SKIPPED)
            }
            val requested = candidate.notices.map { ActorRecipientPair(it.actorId, recipientId) }.toSet()
            val freshAttemptAt = clock()
            attemptAt = freshAttemptAt
            val admitted = store.reservePairs(requested, freshAttemptAt, freshAttemptAt - actorRecipientIntervalMillis)
            reserved = admitted.isNotEmpty()
            blocked = candidate.notices.filter { ActorRecipientPair(it.actorId, recipientId) !in admitted }.map { it.id }.sorted()
            val allowed = candidate.notices.filter { ActorRecipientPair(it.actorId, recipientId) in admitted }
            if (allowed.isEmpty()) {
                released = releaseBlocked(blocked, claimStamp)
                return DigestDeliveryResult(DigestDeliveryStatus.PACED, released)
            }
            val mail = candidate.render(allowed)
            markAttempted()
            try {
                mailer.send(mail.email, mail.subject, mail.body)
            } finally {
                // Do not delay SMTP behind claim release. The release is a bounded, ordered statement and still runs on cancellation.
                released = releaseBlocked(blocked, claimStamp)
            }
            audit("notification.email_sent", "userId" to recipientId.toLong(), "count" to mail.count)
            return DigestDeliveryResult(DigestDeliveryStatus.SENT, released)
        } catch (e: CancellationException) {
            if (reserved) {
                released += releaseBlocked(blocked, claimStamp)
                markAttempted()
            }
            throw e
        } catch (e: Exception) {
            if (reserved) released += releaseBlocked(blocked, claimStamp)
            markAttempted()
            audit("notification.email_failed", "userId" to recipientId.toLong(), "errorType" to e.javaClass.simpleName)
            log.warn("notification email failed ({})", e.javaClass.simpleName)
            return DigestDeliveryResult(DigestDeliveryStatus.FAILED, released)
        }
    }

    private suspend fun releaseBlocked(ids: List<UInt>, stamp: Long): Int = withContext(NonCancellable) {
        if (ids.isEmpty()) 0 else store.release(ids, stamp)
    }

    private suspend fun resolve(recipientId: UInt, claimed: List<ClaimedNotice>): DigestCandidate? = suspendTransaction(database) {
        val users = UserService.Users
        val user = users.select(users.name, users.email, users.language)
            .where { (users.id eq recipientId) and users.active() }.toList().singleOrNull()
            ?: return@suspendTransaction null
        val n = NotificationService.Notifications
        val pending = claimed.map { it.id }.chunked(RECHECK_CHUNK).flatMap { chunk ->
            n.select(n.id).where { (n.id inList chunk) and n.seenAt.isNull() }.map { it[n.id].value }.toList()
        }.toSet()
        val roomNames = memberRooms(recipientId).associate { it.id to it.name }
        val switches = effectivePreferences(recipientId)
        val kept = claimed.filter {
            it.id in pending && it.roomId in roomNames && switches.on(it.type, NotificationChannel.EMAIL)
        }
        val threads = ThreadService.Threads
        val numbers = kept.mapNotNull { it.threadId }.distinct().chunked(RECHECK_CHUNK).flatMap { chunk ->
            threads.select(threads.id, threads.number).where { (threads.id inList chunk) and threadNotMutedBy(threads.id, recipientId) }
                .map { it[threads.id].value to it[threads.number] }.toList()
        }.toMap()
        val survivors = kept.filter { it.threadId == null || it.threadId in numbers }.sortedBy { it.rank }
        if (survivors.isEmpty()) return@suspendTransaction null
        val actors = userNames(survivors.map { it.actorId })
        val teamNames = teamMentionNames(
            recipientId,
            survivors.filter { it.type == NotificationType.TEAM_MENTIONED }.mapNotNull { it.messageId },
        )
        DigestCandidate(
            recipientName = user[users.name],
            email = user[users.email],
            language = user[users.language],
            notices = survivors,
            roomNames = roomNames,
            threadNumbers = numbers,
            actorNames = actors,
            teamNames = teamNames,
            appUrl = appUrl,
        )
    }

    private data class DigestCandidate(
        val recipientName: String,
        val email: String,
        val language: String,
        val notices: List<ClaimedNotice>,
        val roomNames: Map<UInt, String>,
        val threadNumbers: Map<UInt, Int>,
        val actorNames: Map<UInt, String>,
        val teamNames: Map<UInt, String>,
        val appUrl: String?,
    ) {
        fun render(allowed: List<ClaimedNotice>): Mail {
            val grouped = groupDigestNotices(allowed)
            val listed = grouped.take(DIGEST_MAX_NOTICES_PER_RECIPIENT)
            val entries = listed.map { groupedNotice ->
                val notice = groupedNotice.notice
                DigestEntry(
                    notice.type,
                    actorNames[notice.actorId].orEmpty(),
                    notice.roomId,
                    roomNames.getValue(notice.roomId),
                    notice.threadId?.let(threadNumbers::getValue),
                    notice.messageId,
                    replyCount = groupedNotice.replyCount,
                    teamName = notice.messageId?.let(teamNames::get),
                )
            }
            val content = notificationEmail(recipientName, entries, appUrl, language, grouped.size - listed.size)
            return Mail(email, content.subject, content.body, grouped.size)
        }
    }

    private data class Mail(val email: String, val subject: String, val body: String, val count: Int)
}
