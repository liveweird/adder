package ch.nokillswit

import ch.nokillswit.notifications.NoticeWriter
import ch.nokillswit.notifications.NotificationRetention
import ch.nokillswit.notifications.NotificationService
import ch.nokillswit.notifications.NotificationType
import ch.nokillswit.threads.Notice
import ch.nokillswit.threads.NoticeReason
import ch.nokillswit.users.UserRole
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory

/** The V51 per-recipient unseen bound, including its cross-room locking and bounded upgrade cleanup. */
class NotificationRetentionTest {
    private val rooms = mutableListOf<UInt>()
    private val users = mutableListOf<UInt>()
    private val templates = mutableListOf<UInt>()

    private data class Scene(val actor: UInt, val recipient: UInt, val room: UInt, val thread: UInt, val number: Int)

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(rooms)
        users.forEach { TestUsers.softDelete(it) }
        templates.forEach { TestTemplates.delete(it) }
    }

    private suspend fun scene(label: String = "retain"): Scene {
        val actor = TestUsers.seed(uniqueEmail("$label-actor"), "pw", role = UserRole.USER).also { users += it }
        val recipient = TestUsers.seed(uniqueEmail("$label-recipient"), "pw", role = UserRole.USER).also { users += it }
        val template = TestThreads.seedTemplate(label).also { templates += it }
        val room = TestRooms.seed(actor, template, "$label ${UUID.randomUUID().toString().take(8)}").also { rooms += it }
        val thread = TestThreads.seed(room, actor)
        TestRooms.addMember(room, recipient)
        return Scene(actor, recipient, room, thread.id, thread.number)
    }

    private fun seedNotice(
        scene: Scene,
        createdAt: Long,
        seenAt: Long? = null,
        inApp: Boolean = true,
        type: NotificationType = NotificationType.THREAD_REPLIED,
    ): UInt = PostgresTestSupport.connect().use { connection ->
        connection.prepareStatement(
            "INSERT INTO notifications (recipient_id, room_id, notification_type, actor_id, thread_id, created_at, seen_at, " +
                "in_app, emailed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
        ).use { statement ->
            statement.setLong(1, scene.recipient.toLong())
            statement.setInt(2, scene.room.toInt())
            statement.setString(3, type.name)
            statement.setLong(4, scene.actor.toLong())
            statement.setInt(5, scene.thread.toInt())
            statement.setLong(6, createdAt)
            statement.setObject(7, seenAt)
            statement.setBoolean(8, inApp)
            statement.setObject(9, if (inApp) createdAt else null)
            statement.executeQuery().use { rows -> rows.next(); rows.getInt(1).toUInt() }
        }
    }

    private fun unseen(recipient: UInt): List<UInt> = noticeIds(recipient, "seen_at IS NULL")

    private fun noticeIds(recipient: UInt, condition: String = "TRUE"): List<UInt> = PostgresTestSupport.connect().use { connection ->
        connection.prepareStatement("SELECT id FROM notifications WHERE recipient_id = ? AND $condition ORDER BY id").use { statement ->
            statement.setLong(1, recipient.toLong())
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.getInt(1).toUInt()) }
            }
        }
    }

    private suspend fun NoticeWriter.tell(scene: Scene, reason: NoticeReason = NoticeReason.THREAD_CLOSED) =
        suspendTransaction(sharedDatabaseForTests()) {
            forThread(scene.room, scene.thread, scene.number, scene.actor, listOf(Notice(scene.recipient, reason)))
        }

    @Test
    fun `concurrent writes in different rooms share one recipient bound`() = runBlocking {
        val first = scene("retain-cross-a")
        val template = TestThreads.seedTemplate("retain-cross-b").also { templates += it }
        val room = TestRooms.seed(first.actor, template, "retain-cross-b ${UUID.randomUUID().toString().take(8)}").also { rooms += it }
        val thread = TestThreads.seed(room, first.actor)
        TestRooms.addMember(room, first.recipient)
        val second = Scene(first.actor, first.recipient, room, thread.id, thread.number)
        seedNotice(first, 1L, type = NotificationType.THREAD_REPLIED)
        val writerA = NoticeWriter(retention = NotificationRetention(2))
        val writerB = NoticeWriter(retention = NotificationRetention(2))

        withTimeout(20_000) {
            coroutineScope {
                listOf(
                    async { writerA.tell(first, NoticeReason.THREAD_CLOSED) },
                    async { writerB.tell(second, NoticeReason.THREAD_ASSIGNED) },
                ).awaitAll()
            }
        }

        assertEquals(2, unseen(first.recipient).size, "the cross-room transactions serialize on the recipient guard")
    }

    @Test
    fun `recipient guards are acquired in ascending order even when events offer reverse audiences`() = runBlocking {
        val first = scene("retain-order")
        val secondRecipient = TestUsers.seed(uniqueEmail("retain-order-second"), "pw", role = UserRole.USER).also { users += it }
        TestRooms.addMember(first.room, secondRecipient)
        val writerA = NoticeWriter(retention = NotificationRetention(10))
        val writerB = NoticeWriter(retention = NotificationRetention(10))

        withTimeout(20_000) {
            coroutineScope {
                listOf(
                    async {
                        suspendTransaction(sharedDatabaseForTests()) {
                            writerA.forThread(
                                first.room, first.thread, first.number, first.actor,
                                listOf(
                                    Notice(first.recipient, NoticeReason.THREAD_CLOSED),
                                    Notice(secondRecipient, NoticeReason.THREAD_CLOSED),
                                ),
                            )
                        }
                    },
                    async {
                        suspendTransaction(sharedDatabaseForTests()) {
                            writerB.forThread(
                                first.room, first.thread, first.number, first.actor,
                                listOf(
                                    Notice(secondRecipient, NoticeReason.THREAD_REOPENED),
                                    Notice(first.recipient, NoticeReason.THREAD_REOPENED),
                                ),
                            )
                        }
                    },
                ).awaitAll()
            }
        }
        assertTrue(unseen(first.recipient).isNotEmpty())
        assertTrue(unseen(secondRecipient).isNotEmpty())
    }

    @Test
    fun `a compliant audience uses one set-oriented overflow probe and no per-recipient guard updates`() = runBlocking {
        val scene = scene("retain-set")
        val secondRecipient = TestUsers.seed(uniqueEmail("retain-set-second"), "pw", role = UserRole.USER).also { users += it }
        TestRooms.addMember(scene.room, secondRecipient)
        val driver = LoggerFactory.getLogger("io.r2dbc.postgresql.QUERY") as ch.qos.logback.classic.Logger
        val saved = driver.level
        val capture = LogCapture(driver.name)
        try {
            driver.level = ch.qos.logback.classic.Level.DEBUG
            suspendTransaction(sharedDatabaseForTests()) {
                NoticeWriter(retention = NotificationRetention(10)).forThread(
                    scene.room, scene.thread, scene.number, scene.actor,
                    listOf(
                        Notice(scene.recipient, NoticeReason.THREAD_CLOSED),
                        Notice(secondRecipient, NoticeReason.THREAD_CLOSED),
                    ),
                )
            }
            val statements = capture.events.map { it.formattedMessage }
            assertEquals(1, statements.count { "SELECT r.recipient_id" in it && "OFFSET CAST(" in it }, statements.toString())
            assertEquals(0, statements.count { "UPDATE notification_recipient_guards" in it }, statements.toString())
        } finally {
            driver.level = saved
            capture.detach()
        }
    }

    @Test
    fun `a locked oldest row makes trimming take the next oldest available rows`() = runBlocking {
        val scene = scene("retain-skip")
        val old = (1L..4L).map { seedNotice(scene, it, type = NotificationType.entries[it.toInt()]) }
        val writer = NoticeWriter(retention = NotificationRetention(3))

        PostgresTestSupport.connect().use { holder ->
            holder.autoCommit = false
            holder.prepareStatement("SELECT id FROM notifications WHERE id = ? FOR UPDATE").use { statement ->
                statement.setInt(1, old.first().toInt())
                statement.executeQuery().use { it.next() }
            }
            try {
                withTimeout(20_000) { writer.tell(scene, NoticeReason.THREAD_REOPENED) }
            } finally {
                holder.rollback()
            }
        }

        val survivors = unseen(scene.recipient)
        assertEquals(3, survivors.size)
        assertTrue(old.first() in survivors, "the locked oldest row is skipped, not waited on")
        assertTrue(old[1] !in survivors, "the next-oldest available row is expired")
    }

    @Test
    fun `mail-only unseen rows count toward the bound seen rows do not and eviction cancels push`() = runBlocking {
        val scene = scene("retain-channels")
        val oldest = seedNotice(scene, 1L, inApp = true, type = NotificationType.THREAD_REPLIED)
        seedNotice(scene, 2L, inApp = false, type = NotificationType.MENTIONED)
        val seen = seedNotice(scene, 3L, seenAt = 3L, inApp = true, type = NotificationType.THREAD_ASSIGNED)
        enqueueRawPush(scene.recipient, oldest)

        val outcome = NoticeWriter(pushAvailable = true, retention = NotificationRetention(2))
            .tell(scene, NoticeReason.THREAD_CLOSED)

        assertEquals(1, outcome.recipients)
        assertEquals(2, unseen(scene.recipient).size, "mail-only is unseen and consumes capacity")
        assertTrue(seen in noticeIds(scene.recipient), "seen history is retained outside the unseen cap")
        assertTrue(oldest !in noticeIds(scene.recipient), "the oldest unseen row expired")
        assertEquals(0, scalar("SELECT count(*) FROM notification_push_queue WHERE notification_id = ?", oldest))
    }

    @Test
    fun `legacy normalization is bounded per recipient and finishes over several passes`() = runBlocking {
        val scene = scene("retain-legacy")
        repeat(8) { seedNotice(scene, it.toLong(), type = NotificationType.entries[it]) }
        markForNormalization(scene.recipient)
        val service = NotificationService(
            sharedDatabaseForTests(), TestRooms.service, retention = NotificationRetention(3),
        )

        assertEquals(2, service.normalizeLegacyUnseen(recipientLimit = 1, prunePerRecipient = 2))
        assertEquals(6, unseen(scene.recipient).size)
        assertEquals(2, service.normalizeLegacyUnseen(recipientLimit = 1, prunePerRecipient = 2))
        assertEquals(4, unseen(scene.recipient).size)
        assertEquals(1, service.normalizeLegacyUnseen(recipientLimit = 1, prunePerRecipient = 2))
        assertEquals(3, unseen(scene.recipient).size)
        assertEquals(0, service.normalizeLegacyUnseen(recipientLimit = 1, prunePerRecipient = 2))
    }

    @Test
    fun `departing membership notices are deleted in ordered batches and roll back with the membership write`() = runBlocking {
        val scene = scene("retain-member")
        repeat(5) { seedNotice(scene, it.toLong(), type = NotificationType.entries[it]) }
        val retention = NotificationRetention(3)

        assertFailsWith<IllegalStateException> {
            suspendTransaction(sharedDatabaseForTests()) {
                retention.lockRecipients(listOf(scene.recipient))
                assertEquals(5, retention.deleteMembershipNotices(scene.room, scene.recipient, batchSize = 2))
                error("membership delete failed")
            }
        }
        assertEquals(5, noticeIds(scene.recipient).size, "the caller's transaction owns the cleanup")

        suspendTransaction(sharedDatabaseForTests()) {
            retention.lockRecipients(listOf(scene.recipient))
            assertEquals(5, retention.deleteMembershipNotices(scene.room, scene.recipient, batchSize = 2))
        }
        assertEquals(emptyList(), noticeIds(scene.recipient))
    }

    @Test
    fun `a failed producing transaction rolls back its guard and notice changes`() = runBlocking {
        val scene = scene("retain-rollback")
        val writer = NoticeWriter(retention = NotificationRetention(3))

        assertFailsWith<IllegalStateException> {
            suspendTransaction(sharedDatabaseForTests()) {
                writer.forThread(
                    scene.room, scene.thread, scene.number, scene.actor,
                    listOf(Notice(scene.recipient, NoticeReason.THREAD_CLOSED)),
                )
                error("rollback")
            }
        }

        assertEquals(emptyList(), unseen(scene.recipient))
    }

    private fun enqueueRawPush(recipient: UInt, notification: UInt) {
        PostgresTestSupport.connect().use { connection ->
            val subscription = connection.prepareStatement(
                "INSERT INTO browser_push_subscriptions " +
                    "(user_id, endpoint_hash, endpoint_encrypted, credential_revision, created_at) " +
                    "SELECT id, decode(md5(?), 'hex'), 'enc:test', credential_revision, 1 FROM users WHERE id = ? RETURNING id",
            ).use { statement ->
                statement.setString(1, UUID.randomUUID().toString())
                statement.setLong(2, recipient.toLong())
                statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
            }
            connection.prepareStatement(
                "INSERT INTO notification_push_queue (notification_id, subscription_id) VALUES (?, ?)",
            ).use { statement ->
                statement.setInt(1, notification.toInt())
                statement.setInt(2, subscription)
                statement.executeUpdate()
            }
        }
    }

    private fun markForNormalization(recipient: UInt) {
        PostgresTestSupport.connect().use { connection ->
            connection.prepareStatement(
                "INSERT INTO notification_recipient_guards (recipient_id, needs_normalization) VALUES (?, TRUE) " +
                    "ON CONFLICT (recipient_id) DO UPDATE SET needs_normalization = TRUE",
            ).use { statement ->
                statement.setLong(1, recipient.toLong())
                statement.executeUpdate()
            }
        }
    }

    private fun scalar(sql: String, value: UInt): Int = PostgresTestSupport.connect().use { connection ->
        connection.prepareStatement(sql).use { statement ->
            statement.setInt(1, value.toInt())
            statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
    }
}
