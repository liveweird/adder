package ch.nokillswit

import ch.nokillswit.infra.JsonInt
import ch.nokillswit.rooms.RoomMembersAddRequest
import ch.nokillswit.rooms.RoomOrganizationPlacement
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JsonIntegersTest {
    @Serializable
    private data class ThreadTarget(val targetNumber: JsonInt)

    @Test
    fun `request integers round trip as numbers including nullable and list element ids`() {
        val request = RoomMembersAddRequest(userIds = listOf(7u, 18u), teamId = null)
        val json = """{"userIds":[7,18]}"""
        assertEquals(json, Json.encodeToString(request))
        assertEquals(request, Json.decodeFromString<RoomMembersAddRequest>(json))
        assertEquals(null, Json.decodeFromString<RoomMembersAddRequest>("""{"teamId":null}""").teamId)
        assertEquals("""{"targetNumber":7}""", Json.encodeToString(ThreadTarget(7)))
        assertEquals(ThreadTarget(7), Json.decodeFromString<ThreadTarget>("""{"targetNumber":7}"""))
    }

    @Test
    fun `string and non-integer tokens fail in scalars and list entries`() {
        for (value in listOf("\"7\"", "\"+7\"", "\"007\"", "7.5", "true", "{}", "[]", "4294967296", "007")) {
            assertFailsWith<SerializationException>(value) {
                Json.decodeFromString<RoomMembersAddRequest>("""{"userIds":[$value]}""")
            }
            assertFailsWith<SerializationException>(value) {
                Json.decodeFromString<RoomOrganizationPlacement>("""{"roomId":$value,"groupId":null}""")
            }
        }
        assertFailsWith<SerializationException> { Json.decodeFromString<ThreadTarget>("""{"targetNumber":2147483648}""") }
        assertFailsWith<SerializationException> { Json.decodeFromString<ThreadTarget>("""{"targetNumber":"7"}""") }
    }
}
