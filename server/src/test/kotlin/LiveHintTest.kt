package ch.nokillswit

import ch.nokillswit.infra.notify.LiveHint
import ch.nokillswit.infra.notify.LiveAccountHint
import ch.nokillswit.infra.notify.LiveKind
import ch.nokillswit.infra.notify.LiveNotification
import ch.nokillswit.infra.notify.MAX_NOTIFY_PAYLOAD_BYTES
import ch.nokillswit.infra.notify.decodeLiveNotification
import ch.nokillswit.infra.notify.notifyLive
import ch.nokillswit.infra.notify.toPayload
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/** The live hint's wire (`live.md` "The NOTIFY wire"): its JSON shape, its size bound and its names, with no database. */
class LiveHintTest {
    private val serialNames: Map<LiveKind, String> = LiveKind.entries.associateWith {
        Json.encodeToString(LiveKind.serializer(), it).trim('"')
    }

    @Test
    fun `the JSON shape is flat, omits what is absent and always carries the version`() {
        assertEquals(
            """{"kind":"message.posted","roomId":12,"threadNumber":7,"messageId":991,"v":1}""",
            LiveHint(LiveKind.MESSAGE_POSTED, 12u, threadNumber = 7, messageId = 991u).toPayload(),
        )
        assertEquals(
            """{"kind":"message.posted","roomId":12,"threadNumber":7,"messageId":991,""" +
                """"mutationId":"2c6c7f12-79a4-4f1a-8dc7-e9686608b20a","originUserId":8,"v":1}""",
            LiveHint(
                LiveKind.MESSAGE_POSTED,
                12u,
                threadNumber = 7,
                messageId = 991u,
                mutationId = "2c6c7f12-79a4-4f1a-8dc7-e9686608b20a",
                originUserId = 8u,
            ).toPayload(),
        )
        assertEquals("""{"kind":"room.updated","roomId":12,"v":1}""", LiveHint(LiveKind.ROOM_UPDATED, 12u).toPayload())
        assertEquals(
            """{"kind":"room.member_added","roomId":3,"targetUserIds":[5,6],"v":1}""",
            LiveHint(LiveKind.ROOM_MEMBER_ADDED, 3u, targetUserIds = listOf(5u, 6u)).toPayload(),
        )
    }

    @Test
    fun `a hint round trips through its payload`() {
        val hint = LiveHint(LiveKind.ROOM_DELETED, 9u, targetUserIds = listOf(1u, 2u))
        assertEquals(hint, Json.decodeFromString(LiveHint.serializer(), hint.toPayload()))
    }

    @Test
    fun `a room hint tolerates extra fields added by a newer sender`() {
        val future = """{"kind":"room.updated","roomId":12,"v":1,"futureCounter":3}"""
        assertEquals(LiveNotification.Room(LiveHint(LiveKind.ROOM_UPDATED, 12u)), decodeLiveNotification(future))
    }

    @Test
    fun `the roomless account envelope round trips but can never be widened into a room event`() {
        val payload = LiveAccountHint(7u).toPayload()
        assertEquals("""{"accountUserId":7,"v":1}""", payload)
        assertEquals(LiveNotification.Account(7u), decodeLiveNotification(payload))
        assertEquals(
            LiveNotification.Room(LiveHint(LiveKind.ROOM_UPDATED, 12u)),
            decodeLiveNotification(LiveHint(LiveKind.ROOM_UPDATED, 12u).toPayload()),
        )
        assertEquals(null, decodeLiveNotification("""{"accountUserId":7,"roomId":12,"v":1}"""))
        assertEquals(null, decodeLiveNotification("""{"accountUserId":0,"v":1}"""))
        assertEquals(null, decodeLiveNotification("""{"accountUserId":7,"v":2}"""))
    }

    @Test
    fun `500 ten-digit targets stay under the NOTIFY payload limit`() {
        val worst = LiveHint(LiveKind.ROOM_MEMBER_ADDED, UInt.MAX_VALUE, targetUserIds = List(500) { UInt.MAX_VALUE - it.toUInt() })
        val bytes = worst.toPayload().toByteArray(Charsets.UTF_8).size
        assertTrue(bytes in 5_000 until MAX_NOTIFY_PAYLOAD_BYTES, "the worst case is $bytes bytes")
    }

    @Test
    fun `the widest notice hint, 500 ten-digit recipients with the widest numbers, stays under the NOTIFY payload limit`() {
        // 1.1: a notice that reaches the whole room is ONE hint to its recipients: 499 when a member acts, 500 when an ADMIN who is no
        // member closes a thread everybody watches. Ten-digit ids everywhere: the true worst case.
        val worst = LiveHint(
            LiveKind.NOTIFICATION_CREATED, UInt.MAX_VALUE, Int.MAX_VALUE, UInt.MAX_VALUE,
            targetUserIds = List(500) { UInt.MAX_VALUE - it.toUInt() },
        )
        val bytes = worst.toPayload().toByteArray(Charsets.UTF_8).size
        assertTrue(bytes in 5_000 until 8_000, "the worst case is $bytes bytes")
        assertTrue(bytes < MAX_NOTIFY_PAYLOAD_BYTES)
        assertEquals(worst, Json.decodeFromString(LiveHint.serializer(), worst.toPayload()))
    }

    @Test
    fun `a hint over the limit is refused before the database sees it`() {
        val tooBig = LiveHint(LiveKind.ROOM_MEMBER_ADDED, 1u, targetUserIds = List(800) { UInt.MAX_VALUE - it.toUInt() })
        // No transaction is needed: the size check comes first.
        val failure = assertFailsWith<IllegalStateException> { runBlocking { notifyLive(tooBig) } }
        assertTrue("exceeds" in failure.message.orEmpty())
    }

    @Test
    fun `the twenty-one kinds have unique dotted audit names`() {
        assertEquals(21, LiveKind.entries.size)
        assertEquals(21, serialNames.values.toSet().size)
        assertTrue(serialNames.values.all { Regex("(room|thread|message|notification)\\.[a-z_]+").matches(it) }, "$serialNames")
    }

    @Test
    fun `every kind is the audit event name of the write that emits it`() {
        val root = File(System.getProperty("repo.root") ?: "..", "server/src/main/kotlin")
        val routeSources = root.walkTopDown().filter { it.isFile && it.name.endsWith("Routes.kt") }.joinToString("\n") { it.readText() }
        serialNames.values.forEach { name ->
            assertTrue(Regex("""audit\(\s*"${Regex.escape(name)}"""").containsMatchIn(routeSources), "no route audits '$name'")
        }
    }
}
