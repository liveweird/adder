package ch.nokillswit

import ch.nokillswit.notifications.DisabledNotificationPreference
import ch.nokillswit.notifications.NotificationChannel
import ch.nokillswit.notifications.NotificationPreferencesResponse
import ch.nokillswit.notifications.NotificationPreferencesUpdateRequest
import ch.nokillswit.notifications.NotificationType
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.AfterTest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The per-user email switches of the notices (V22, Lettuce's preferences model): everything on by default, a wholesale replace of the
 * DISABLED pairs, self or ADMIN only, and the refusal order 403, 400, 404. Users are unique rows (soft-deleted ones stay inert).
 */
class NotificationPreferencesTest {
    private companion object {
        const val MENTION_JSON = """{"type":"MENTIONED","channel":"EMAIL"}"""
        const val UNKNOWN_KIND = """{"disabled":[{"type":"NO_SUCH_KIND","channel":"EMAIL"}]}"""
    }

    private fun ch.qos.logback.classic.spi.ILoggingEvent.isChangeOf(id: UInt) =
        message == "user.notification_preferences_changed" && hasKeyValue("targetUserId", id.toLong())

    private val minted = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking {
        minted.forEach { TestUsers.softDelete(it) }
    }

    /** A signed-in user (the seeded id is tracked for cleanup); the client alone, as `seededClient` answers. */
    private suspend fun ApplicationTestBuilder.actor(prefix: String, role: UserRole): HttpClient =
        seedActor(prefix, role).also { minted += it.id }.client

    private fun path(id: UInt) = "/api/v1/users/$id/notification-preferences"

    private val mentionOff = DisabledNotificationPreference(NotificationType.MENTIONED, NotificationChannel.EMAIL)
    private val assignedOff = DisabledNotificationPreference(NotificationType.THREAD_ASSIGNED, NotificationChannel.EMAIL)

    private suspend fun HttpClient.rawPut(id: UInt, json: String): HttpResponse = put(path(id)) {
        contentType(ContentType.Application.Json)
        setBody(json)
    }

    private suspend fun HttpClient.switches(id: UInt): Map<NotificationType, Boolean> =
        get(path(id)).body<NotificationPreferencesResponse>().kinds.associate { it.type to it.email }

    @Test
    fun `every kind is on by default`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("pref-default")
        val id = TestUsers.seed(email, "pw", role = UserRole.USER).also { minted += it }
        val client = authedClient(email, "pw")

        val response = client.get(path(id))
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<NotificationPreferencesResponse>()
        assertEquals(NotificationType.entries.toList(), body.kinds.map { it.type })
        assertTrue(body.kinds.all { it.email }, "no stored row means on")
        // The test harness boots with the log transport: a mailer exists.
        assertTrue(body.emailAvailable)
    }

    @Test
    fun `the PUT round-trips, replaces wholesale and is idempotent`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("pref-trip")
        val id = TestUsers.seed(email, "pw", role = UserRole.USER).also { minted += it }
        val client = authedClient(email, "pw")

        assertEquals(HttpStatusCode.NoContent, client.putJson(path(id), NotificationPreferencesUpdateRequest(listOf(mentionOff))).status)
        assertEquals(mapOf(NotificationType.MENTIONED to false, NotificationType.THREAD_ASSIGNED to true), client.switches(id))

        assertEquals(HttpStatusCode.NoContent, client.putJson(path(id), NotificationPreferencesUpdateRequest(listOf(mentionOff))).status)
        assertEquals(false, client.switches(id).getValue(NotificationType.MENTIONED), "a same-set PUT changes nothing")

        // Wholesale: the second set replaces the first, it does not add to it.
        assertEquals(HttpStatusCode.NoContent, client.putJson(path(id), NotificationPreferencesUpdateRequest(listOf(assignedOff))).status)
        assertEquals(mapOf(NotificationType.MENTIONED to true, NotificationType.THREAD_ASSIGNED to false), client.switches(id))

        assertEquals(HttpStatusCode.NoContent, client.putJson(path(id), NotificationPreferencesUpdateRequest(emptyList())).status)
        assertTrue(client.switches(id).values.all { it })
    }

    @Test
    fun `an unknown kind or channel and a repeated pair are 400`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("pref-bad")
        val id = TestUsers.seed(email, "pw", role = UserRole.USER).also { minted += it }
        val client = authedClient(email, "pw")

        for (json in listOf(
            UNKNOWN_KIND,
            """{"disabled":[{"type":"MENTIONED","channel":"SMS"}]}""",
            """{"disabled":[{"type":"MENTIONED","channel":"IN_APP"}]}""",
            """{"disabled":[$MENTION_JSON,$MENTION_JSON]}""",
            """{"disabled":[$MENTION_JSON,{"type":"THREAD_ASSIGNED","channel":"EMAIL"},$MENTION_JSON]}""",
            """{}""",
        )) {
            assertEquals(HttpStatusCode.BadRequest, client.rawPut(id, json).status, json)
        }
        assertTrue(client.switches(id).values.all { it }, "a refused PUT stores nothing")
    }

    @Test
    fun `self and an ADMIN may act and anyone else is 403 before any 400`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("pref-who")
        val id = TestUsers.seed(email, "pw", role = UserRole.USER).also { minted += it }
        val admin = actor("pref-admin", UserRole.ADMIN)
        val stranger = actor("pref-stranger", UserRole.USER)

        assertEquals(HttpStatusCode.NoContent, admin.putJson(path(id), NotificationPreferencesUpdateRequest(listOf(mentionOff))).status)
        assertEquals(false, admin.switches(id).getValue(NotificationType.MENTIONED), "an ADMIN reads another person's switches")

        assertEquals(HttpStatusCode.Forbidden, stranger.get(path(id)).status)
        assertEquals(HttpStatusCode.Forbidden, stranger.putJson(path(id), NotificationPreferencesUpdateRequest(emptyList())).status)
        assertEquals(HttpStatusCode.Forbidden, stranger.rawPut(id, UNKNOWN_KIND).status, "403 wins over 400")
        assertEquals(HttpStatusCode.Forbidden, stranger.rawPut(999_999u, "{}").status, "and over 404")
        assertEquals(false, admin.switches(id).getValue(NotificationType.MENTIONED), "a refused write changed nothing")
    }

    @Test
    fun `an unknown or deleted target is 404`() = testApplication {
        usePostgresTestcontainer()
        val admin = actor("pref-404", UserRole.ADMIN)
        val email = uniqueEmail("pref-gone")
        val id = TestUsers.seed(email, "pw", role = UserRole.USER).also { minted += it }
        TestUsers.softDelete(id)

        assertEquals(HttpStatusCode.NotFound, admin.get(path(999_999u)).status)
        assertEquals(HttpStatusCode.NotFound, admin.get(path(id)).status)
        assertEquals(HttpStatusCode.NotFound, admin.putJson(path(999_999u), NotificationPreferencesUpdateRequest(emptyList())).status)
        assertEquals(HttpStatusCode.NotFound, admin.putJson(path(id), NotificationPreferencesUpdateRequest(listOf(mentionOff))).status)
        // A bad body is a 400 even for an absent target: the order is 403, 400, 404.
        assertEquals(HttpStatusCode.BadRequest, admin.rawPut(999_999u, UNKNOWN_KIND).status)
    }

    @Test
    fun `a change is audited with the sets before and after and an unchanged one is not`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("pref-audit")
        val id = TestUsers.seed(email, "pw", role = UserRole.USER).also { minted += it }
        val client = authedClient(email, "pw")

        withAuditCapture { capture ->
            client.putJson(path(id), NotificationPreferencesUpdateRequest(listOf(assignedOff, mentionOff)))
            val event = capture.awaitEvent { it.isChangeOf(id) }
            assertNotNull(event)
            assertTrue(event.hasKeyValue("byUserId", id.toLong()))
            assertTrue(event.hasKeyValue("from", ""))
            assertTrue(event.hasKeyValue("to", "MENTIONED:EMAIL,THREAD_ASSIGNED:EMAIL"))

            client.putJson(path(id), NotificationPreferencesUpdateRequest(listOf(mentionOff, assignedOff)))
            client.putJson(path(id), NotificationPreferencesUpdateRequest(listOf(mentionOff)))
            val changes = capture.events.filter { it.isChangeOf(id) }
            assertEquals(2, changes.size, "the same set again is silent; the narrower one is a change")
            assertTrue(changes.last().hasKeyValue("from", "MENTIONED:EMAIL,THREAD_ASSIGNED:EMAIL"))
            assertTrue(changes.last().hasKeyValue("to", "MENTIONED:EMAIL"))
        }
    }

    @Test
    fun `a stored name this build does not know is ignored`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("pref-open")
        val id = TestUsers.seed(email, "pw", role = UserRole.USER).also { minted += it }
        val client = authedClient(email, "pw")
        PostgresTestSupport.connect().use { c ->
            val insert = "INSERT INTO user_notification_preferences VALUES (?, 'A_FUTURE_KIND', 'EMAIL'), (?, 'MENTIONED', 'PUSH')"
            c.prepareStatement(insert).use { st ->
                st.setLong(1, id.toLong())
                st.setLong(2, id.toLong())
                st.executeUpdate()
            }
        }

        assertTrue(client.switches(id).values.all { it }, "an unknown kind or channel switches nothing off")
        assertEquals(HttpStatusCode.NoContent, client.putJson(path(id), NotificationPreferencesUpdateRequest(emptyList())).status)
    }
}
