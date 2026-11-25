package ch.nokillswit

import ch.nokillswit.notifications.NotificationChannel
import ch.nokillswit.notifications.NotificationPreferencePair
import ch.nokillswit.notifications.NotificationPreferencesResponse
import ch.nokillswit.notifications.NotificationPreferencesUpdateRequest
import ch.nokillswit.notifications.NotificationType
import ch.nokillswit.notifications.THREAD_NOTIFICATION_TYPES
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
 * The per-user notification switches (V22, reshaped by V28; 1.1 D7): in the app ON and by email OFF by default for every kind, a wholesale
 * replace (`disabled` pairs are off, `enabled` pairs are on, a pair in neither takes its default), self or ADMIN only, and the
 * refusal order
 * 403, 400, 404. Users are unique rows (soft-deleted ones stay inert).
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

    private fun pair(type: NotificationType, channel: NotificationChannel) = NotificationPreferencePair(type, channel)

    private val mentionEmail = pair(NotificationType.MENTIONED, NotificationChannel.EMAIL)
    private val assignedEmail = pair(NotificationType.THREAD_ASSIGNED, NotificationChannel.EMAIL)
    private val mentionApp = pair(NotificationType.MENTIONED, NotificationChannel.IN_APP)
    private val assignedApp = pair(NotificationType.THREAD_ASSIGNED, NotificationChannel.IN_APP)

    private suspend fun HttpClient.rawPut(id: UInt, json: String): HttpResponse = put(path(id)) {
        contentType(ContentType.Application.Json)
        setBody(json)
    }

    private suspend fun HttpClient.kinds(id: UInt) = get(path(id)).body<NotificationPreferencesResponse>().kinds

    /** The (type, channel) pairs that are on. */
    private suspend fun HttpClient.on(id: UInt): Set<NotificationPreferencePair> = kinds(id).flatMap { k ->
        listOfNotNull(
            if (k.inApp) pair(k.type, NotificationChannel.IN_APP) else null,
            if (k.email) pair(k.type, NotificationChannel.EMAIL) else null,
        )
    }.toSet()

    private val defaults = THREAD_NOTIFICATION_TYPES.map { pair(it, NotificationChannel.IN_APP) }.toSet()

    @Test
    fun `every kind is on in the app and off by email by default`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("pref-default")
        val id = TestUsers.seed(email, "pw", role = UserRole.USER).also { minted += it }
        val client = authedClient(email, "pw")

        val response = client.get(path(id))
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<NotificationPreferencesResponse>()
        assertEquals(THREAD_NOTIFICATION_TYPES.toList(), body.kinds.map { it.type })
        assertEquals(13, body.kinds.size)
        assertTrue(body.kinds.all { it.inApp }, "the app is on for every kind")
        assertTrue(body.kinds.none { it.email }, "email is off for every kind, the two 1.0 kinds included")
        // The test harness boots with the log transport: a mailer exists.
        assertTrue(body.emailAvailable)
    }

    @Test
    fun `the PUT round-trips, replaces wholesale and is idempotent`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("pref-trip")
        val id = TestUsers.seed(email, "pw", role = UserRole.USER).also { minted += it }
        val client = authedClient(email, "pw")

        val first = NotificationPreferencesUpdateRequest(listOf(mentionApp), listOf(assignedEmail))
        assertEquals(HttpStatusCode.NoContent, client.putJson(path(id), first).status)
        assertEquals(defaults - mentionApp + assignedEmail, client.on(id))

        assertEquals(HttpStatusCode.NoContent, client.putJson(path(id), first).status)
        assertEquals(defaults - mentionApp + assignedEmail, client.on(id), "a same-state PUT changes nothing")

        // Wholesale: the second request replaces the first, it does not add to it.
        val second = NotificationPreferencesUpdateRequest(listOf(assignedApp), listOf(mentionEmail))
        assertEquals(HttpStatusCode.NoContent, client.putJson(path(id), second).status)
        assertEquals(defaults - assignedApp + mentionEmail, client.on(id))

        assertEquals(HttpStatusCode.NoContent, client.putJson(path(id), NotificationPreferencesUpdateRequest(emptyList())).status)
        assertEquals(defaults, client.on(id), "an empty body is every default")
    }

    @Test
    fun `an in-app pair that says its default is not stored, an email-off pair is, and a legacy row reads as off`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("pref-norm")
        val id = TestUsers.seed(email, "pw", role = UserRole.USER).also { minted += it }
        val client = authedClient(email, "pw")

        // The app on IS the default: nothing to store. Email off is the default too, but its "off" is kept as an explicit row (1.1
        // ignores it;
        // a build rolled back to 1.0.0 still reads it as the person's opt-out).
        client.putJson(path(id), NotificationPreferencesUpdateRequest(listOf(mentionEmail), listOf(assignedApp)))
        assertEquals(1, storedRows(id))
        assertEquals(listOf(false), storedFlags(id, "MENTIONED", "EMAIL"))
        assertEquals(defaults, client.on(id), "and the switches read the defaults")

        // A V22 row (written by 1.0.0, `enabled` left to its default FALSE) is "switched off", whatever the default is today.
        PostgresTestSupport.connect().use { c ->
            val legacy = "INSERT INTO user_notification_preferences (user_id, notification_type, channel) VALUES (?, 'MENTIONED', 'IN_APP')"
            c.prepareStatement(legacy).use { st ->
                st.setLong(1, id.toLong())
                st.executeUpdate()
            }
        }
        assertEquals(defaults - mentionApp, client.on(id), "the legacy row still means off")
        // Saved again, the page's own state (every pair) keeps it a deviation: one row, enabled = false.
        client.putJson(path(id), NotificationPreferencesUpdateRequest(listOf(mentionApp)))
        assertEquals(1, storedRows(id))
        assertEquals(defaults - mentionApp, client.on(id))
    }

    private fun storedFlags(id: UInt, type: String, channel: String): List<Boolean> = PostgresTestSupport.connect().use { c ->
        val sql = "SELECT enabled FROM user_notification_preferences WHERE user_id = ? AND notification_type = ? AND channel = ?"
        c.prepareStatement(sql).use { st ->
            st.setLong(1, id.toLong())
            st.setString(2, type)
            st.setString(3, channel)
            st.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getBoolean(1) else null }.toList() }
        }
    }

    private fun storedRows(id: UInt): Int = PostgresTestSupport.connect().use { c ->
        c.prepareStatement("SELECT count(*) FROM user_notification_preferences WHERE user_id = ?").use { st ->
            st.setLong(1, id.toLong())
            st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    @Test
    fun `a 1_0 opt-out row survives a save, and a full save stores one multi-row set`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("pref-keep")
        val id = TestUsers.seed(email, "pw", role = UserRole.USER).also { minted += it }
        val client = authedClient(email, "pw")
        TestNotificationPreferences.set(id, NotificationType.MENTIONED, NotificationChannel.EMAIL, enabled = false) // what 1.0.0 wrote

        // The 1.1 page sends every pair: all the email pairs off, the app pairs on except one.
        val all = THREAD_NOTIFICATION_TYPES.flatMap { type -> NotificationChannel.entries.map { pair(type, it) } }
        val off = all.filter { it.channel == NotificationChannel.EMAIL || it == mentionApp }
        val request = NotificationPreferencesUpdateRequest(off, all - off.toSet())
        assertEquals(HttpStatusCode.NoContent, client.putJson(path(id), request).status)

        assertEquals(listOf(false), storedFlags(id, "MENTIONED", "EMAIL"), "the opt-out is still an explicit row")
        assertEquals(THREAD_NOTIFICATION_TYPES.size + 1, storedRows(id), "every email-off pair plus the one in-app deviation")
        assertEquals(defaults - mentionApp, client.on(id), "1.1 reads the deviation and ignores the redundant rows")
    }

    @Test
    fun `an ADMIN changing another person's switches is audited with both ids`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("pref-onbehalf")
        val id = TestUsers.seed(email, "pw", role = UserRole.USER).also { minted += it }
        val adminActor = seedActor("pref-by-admin", UserRole.ADMIN).also { minted += it.id }

        withAuditCapture { capture ->
            val request = NotificationPreferencesUpdateRequest(listOf(mentionApp))
            assertEquals(HttpStatusCode.NoContent, adminActor.client.putJson(path(id), request).status)
            val event = assertNotNull(capture.awaitEvent { it.isChangeOf(id) })
            assertTrue(event.hasKeyValue("byUserId", adminActor.id.toLong()))
            assertTrue(event.hasKeyValue("targetUserId", id.toLong()))
            assertTrue(adminActor.id != id)
        }
    }

    @Test
    fun `a 1_0 client that sends only disabled cannot switch an email on`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("pref-old")
        val id = TestUsers.seed(email, "pw", role = UserRole.USER).also { minted += it }
        val client = authedClient(email, "pw")
        TestNotificationPreferences.optInEmail(id, listOf(NotificationType.THREAD_CLOSED))
        assertTrue(pair(NotificationType.THREAD_CLOSED, NotificationChannel.EMAIL) in client.on(id))

        // 1.0.0's "everything on" body: an empty disabled list. Unlisted = default, so the opt-in goes and nothing new comes on.
        assertEquals(HttpStatusCode.NoContent, client.rawPut(id, """{"disabled":[]}""").status)

        assertEquals(defaults, client.on(id))
    }

    @Test
    fun `an unknown kind or channel, a repeated pair and a pair in both lists are 400`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("pref-bad")
        val id = TestUsers.seed(email, "pw", role = UserRole.USER).also { minted += it }
        val client = authedClient(email, "pw")

        for (json in listOf(
            UNKNOWN_KIND,
            """{"disabled":[{"type":"MENTIONED","channel":"SMS"}]}""",
            """{"disabled":[$MENTION_JSON,$MENTION_JSON]}""",
            """{"disabled":[$MENTION_JSON,{"type":"THREAD_ASSIGNED","channel":"EMAIL"},$MENTION_JSON]}""",
            """{"disabled":[],"enabled":[$MENTION_JSON,$MENTION_JSON]}""",
            """{"disabled":[$MENTION_JSON],"enabled":[$MENTION_JSON]}""",
            """{"disabled":[],"enabled":[{"type":"NO_SUCH_KIND","channel":"EMAIL"}]}""",
            """{"enabled":[$MENTION_JSON]}""",
            """{}""",
        )) {
            assertEquals(HttpStatusCode.BadRequest, client.rawPut(id, json).status, json)
        }
        assertEquals(defaults, client.on(id), "a refused PUT stores nothing")
        assertEquals(HttpStatusCode.NoContent, client.rawPut(id, """{"disabled":[{"type":"MENTIONED","channel":"IN_APP"}]}""").status)
    }

    @Test
    fun `self and an ADMIN may act and anyone else is 403 before any 400`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("pref-who")
        val id = TestUsers.seed(email, "pw", role = UserRole.USER).also { minted += it }
        val admin = actor("pref-admin", UserRole.ADMIN)
        val stranger = actor("pref-stranger", UserRole.USER)

        assertEquals(HttpStatusCode.NoContent, admin.putJson(path(id), NotificationPreferencesUpdateRequest(listOf(mentionApp))).status)
        assertEquals(defaults - mentionApp, admin.on(id), "an ADMIN reads another person's switches")

        assertEquals(HttpStatusCode.Forbidden, stranger.get(path(id)).status)
        assertEquals(HttpStatusCode.Forbidden, stranger.putJson(path(id), NotificationPreferencesUpdateRequest(emptyList())).status)
        assertEquals(HttpStatusCode.Forbidden, stranger.rawPut(id, UNKNOWN_KIND).status, "403 wins over 400")
        assertEquals(HttpStatusCode.Forbidden, stranger.rawPut(999_999u, "{}").status, "and over 404")
        assertEquals(defaults - mentionApp, admin.on(id), "a refused write changed nothing")
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
        assertEquals(HttpStatusCode.NotFound, admin.putJson(path(id), NotificationPreferencesUpdateRequest(listOf(mentionApp))).status)
        // A bad body is a 400 even for an absent target: the order is 403, 400, 404.
        assertEquals(HttpStatusCode.BadRequest, admin.rawPut(999_999u, UNKNOWN_KIND).status)
    }

    @Test
    fun `a change is audited with the deviations before and after and an unchanged one is not`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("pref-audit")
        val id = TestUsers.seed(email, "pw", role = UserRole.USER).also { minted += it }
        val client = authedClient(email, "pw")

        withAuditCapture { capture ->
            client.putJson(path(id), NotificationPreferencesUpdateRequest(listOf(assignedApp, mentionApp), listOf(mentionEmail)))
            val event = capture.awaitEvent { it.isChangeOf(id) }
            assertNotNull(event)
            assertTrue(event.hasKeyValue("byUserId", id.toLong()))
            assertTrue(event.hasKeyValue("from", ""))
            assertTrue(event.hasKeyValue("to", "MENTIONED:EMAIL=on,MENTIONED:IN_APP=off,THREAD_ASSIGNED:IN_APP=off"))

            client.putJson(path(id), NotificationPreferencesUpdateRequest(listOf(mentionApp, assignedApp), listOf(mentionEmail)))
            // Email off and the app on are the defaults: a PUT that only says so changes the person's deviations, which is audited.
            client.putJson(path(id), NotificationPreferencesUpdateRequest(listOf(mentionApp), listOf(mentionEmail, assignedApp)))
            val changes = capture.events.filter { it.isChangeOf(id) }
            assertEquals(2, changes.size, "the same state again is silent; the narrower one is a change")
            assertTrue(changes.last().hasKeyValue("from", "MENTIONED:EMAIL=on,MENTIONED:IN_APP=off,THREAD_ASSIGNED:IN_APP=off"))
            assertTrue(changes.last().hasKeyValue("to", "MENTIONED:EMAIL=on,MENTIONED:IN_APP=off"))
        }
    }

    @Test
    fun `a stored name this build does not know is ignored`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("pref-open")
        val id = TestUsers.seed(email, "pw", role = UserRole.USER).also { minted += it }
        val client = authedClient(email, "pw")
        PostgresTestSupport.connect().use { c ->
            val insert = "INSERT INTO user_notification_preferences VALUES (?, 'A_FUTURE_KIND', 'EMAIL', true), " +
                "(?, 'MENTIONED', 'PUSH', false)"
            c.prepareStatement(insert).use { st ->
                st.setLong(1, id.toLong())
                st.setLong(2, id.toLong())
                st.executeUpdate()
            }
        }

        assertEquals(defaults, client.on(id), "an unknown kind or channel switches nothing")
        assertEquals(HttpStatusCode.NoContent, client.putJson(path(id), NotificationPreferencesUpdateRequest(emptyList())).status)
    }

    @Test
    fun `the 31st preferences PUT in a minute is 429 on its own bucket and the GET is not bucketed`() = testApplication {
        usePostgresTestcontainer()
        val actor = seedActor("pref-bucket").also { minted += it.id }
        withAuditCapture { capture ->
            repeat(30) { n ->
                assertEquals(HttpStatusCode.NoContent, actor.client.rawPut(actor.id, """{"disabled":[]}""").status, "write ${n + 1}")
            }
            val rejected = actor.client.rawPut(actor.id, """{"disabled":[]}""")
            assertEquals(HttpStatusCode.TooManyRequests, rejected.status)
            assertNotNull(rejected.headers["Retry-After"])
            val event = assertNotNull(
                capture.awaitEvent { it.message == "rate_limit.exceeded" && it.hasKeyValue("bucket", "notification-prefs") },
            )
            assertTrue(event.hasKeyValue("byUserId", actor.id.toLong()))
            assertEquals(HttpStatusCode.OK, actor.client.get(path(actor.id)).status, "the GET is not bucketed")
        }
    }
}
