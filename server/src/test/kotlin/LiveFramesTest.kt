package ch.nokillswit

import ch.nokillswit.infra.notify.LiveKind
import ch.nokillswit.live.LiveClientFrame
import ch.nokillswit.live.LiveServerFrame
import ch.nokillswit.live.MAX_SUBSCRIPTIONS
import ch.nokillswit.live.parseClientFrame
import ch.nokillswit.live.toJson
import ch.nokillswit.live.validIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The wire frames of the live socket (`live/LiveFrames.kt`, `live.md` "Frames and close codes"): strict in, lean out. */
class LiveFramesTest {
    @Test
    fun `the client frames parse`() {
        assertEquals(LiveClientFrame.Auth("t".repeat(43)), parseClientFrame("""{"type":"auth","ticket":"${"t".repeat(43)}"}"""))
        assertEquals(LiveClientFrame.Subscribe(listOf(12, 40)), parseClientFrame("""{"type":"subscribe","roomIds":[12,40]}"""))
        assertEquals(LiveClientFrame.Unsubscribe(listOf(40)), parseClientFrame("""{"type":"unsubscribe","roomIds":[40]}"""))
        assertEquals(LiveClientFrame.Ping, parseClientFrame("""{"type":"ping"}"""))
        assertEquals(LiveClientFrame.Foreground(true), parseClientFrame("""{"type":"foreground","visible":true}"""))
    }

    @Test
    fun `anything else is refused - unknown type, unknown field, missing field, wrong type, not JSON`() {
        listOf(
            """{"type":"nope"}""",
            """{"roomIds":[1]}""",
            """{"type":"ping","extra":1}""",
            """{"type":"subscribe"}""",
            """{"type":"subscribe","roomIds":"1"}""",
            """{"type":"subscribe","roomIds":[1.5]}""",
            """{"type":"auth","ticket":7}""",
            """{"type":"auth"}""",
            "ping",
            "",
            "[]",
            """{"type":"ping"} trailing""",
        ).forEach { text -> assertFailsWith<Exception>(text) { parseClientFrame(text) } }
    }

    @Test
    fun `room ids are 1 to 128 positive unsigned ints, de-duplicated`() {
        assertEquals(listOf(1u, 2u), validIds(listOf(1, 2, 1)))
        assertEquals(listOf(UInt.MAX_VALUE), validIds(listOf(UInt.MAX_VALUE.toLong())))
        assertNull(validIds(emptyList()))
        assertNull(validIds(listOf(0)))
        assertNull(validIds(listOf(-1)))
        assertNull(validIds(listOf(UInt.MAX_VALUE.toLong() + 1)))
        assertEquals((1..MAX_SUBSCRIPTIONS).map { it.toUInt() }, validIds((1..MAX_SUBSCRIPTIONS).map { it.toLong() }))
        assertNull(validIds((1..MAX_SUBSCRIPTIONS + 1).map { it.toLong() }), "129 ids in one frame")
    }

    @Test
    fun `server frames serialize as the spec says`() {
        assertEquals("""{"type":"ready","protocol":1,"maxSubscriptions":128,"presenceSupported":true}""", LiveServerFrame.Ready().toJson())
        assertEquals(
            """{"type":"subscribed","roomIds":[12],"refused":[40],"capped":[]}""",
            LiveServerFrame.Subscribed(listOf(12u), listOf(40u), emptyList()).toJson(),
        )
        assertEquals(
            """{"type":"event","kind":"message.posted","roomId":12,"threadNumber":7,"messageId":991}""",
            LiveServerFrame.Event(LiveKind.MESSAGE_POSTED, 12u, 7, 991u).toJson(),
        )
        assertEquals("""{"type":"event","kind":"room.updated","roomId":12}""", LiveServerFrame.Event(LiveKind.ROOM_UPDATED, 12u).toJson())
        assertEquals("""{"type":"revoked","roomId":12}""", LiveServerFrame.Revoked(12u).toJson())
        assertEquals("""{"type":"resync"}""", LiveServerFrame.Resync.toJson())
        assertEquals("""{"type":"pong"}""", LiveServerFrame.Pong.toJson())
    }

    @Test
    fun `the auth frame never renders its ticket`() {
        val ticket = "t".repeat(43)
        val frame = parseClientFrame("""{"type":"auth","ticket":"$ticket"}""")
        assertFalse(ticket in frame.toString())
        assertTrue("***" in frame.toString())
    }
}
