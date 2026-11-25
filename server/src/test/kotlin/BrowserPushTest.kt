package ch.nokillswit

import ch.nokillswit.notifications.BrowserPushConfigResponse
import ch.nokillswit.notifications.BrowserPushServiceKey
import ch.nokillswit.notifications.BrowserPushSender
import ch.nokillswit.notifications.BrowserPushSubscriptionRequest
import ch.nokillswit.notifications.BrowserPushSubscriptionResponse
import ch.nokillswit.notifications.NotificationSweepKey
import ch.nokillswit.notifications.PushResult
import ch.nokillswit.notifications.PushTransport
import ch.nokillswit.authz.UnauthorizedException
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class BrowserPushTest {
    private val users = mutableListOf<UInt>()
    private val templates = mutableListOf<UInt>()
    private val rooms = mutableListOf<UInt>()
    private val minted = MintedIds(users, templates, rooms)

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(rooms)
        PostgresTestSupport.connect().use { connection ->
            connection.prepareStatement("DELETE FROM browser_push_subscriptions WHERE user_id = ?").use { statement ->
                for (user in users) {
                    statement.setInt(1, user.toInt())
                    statement.executeUpdate()
                }
            }
        }
        users.forEach { TestUsers.softDelete(it) }
        templates.forEach { TestTemplates.delete(it) }
    }

    @Test
    fun `capability is false by default and registration is unavailable`() = testApplication {
        usePostgresTestcontainer()
        val actor = seedActor("push-off").also { users += it.id }
        assertEquals(BrowserPushConfigResponse(false), actor.client.get("$API/config").body())
        assertEquals(
            BrowserPushConfigResponse(available = false, registered = false),
            actor.client.get("$API/config?subscriptionId=1").body(),
        )
        val response = actor.client.postJson("$API/subscriptions", BrowserPushSubscriptionRequest(ENDPOINT))
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals(
            HttpStatusCode.PayloadTooLarge,
            actor.client.postJson(
                "$API/subscriptions",
                BrowserPushSubscriptionRequest("x".repeat(20 * 1024)),
            ).status,
        )
    }

    @Test
    fun `config reconciles only the callers current unexpired subscription`() = testApplication {
        configureApp(*vapidConfig())
        val owner = seedActor("push-reconcile-owner").also { users += it.id }
        val other = seedActor("push-reconcile-other").also { users += it.id }
        val plain = owner.client.get("$API/config").body<kotlinx.serialization.json.JsonObject>()
        assertFalse("registered" in plain)
        val id = owner.client.postJson("$API/subscriptions", BrowserPushSubscriptionRequest(ENDPOINT))
            .body<BrowserPushSubscriptionResponse>().id
        assertTrue(owner.client.get("$API/config?subscriptionId=$id").body<BrowserPushConfigResponse>().registered == true)
        assertFalse(other.client.get("$API/config?subscriptionId=$id").body<BrowserPushConfigResponse>().registered == true)
        assertFalse(owner.client.get("$API/config?subscriptionId=${id + 1u}").body<BrowserPushConfigResponse>().registered == true)
        assertEquals(HttpStatusCode.BadRequest, owner.client.get("$API/config?subscriptionId=0").status)

        updateSubscriptionRevision(id, 1)
        assertFalse(owner.client.get("$API/config?subscriptionId=$id").body<BrowserPushConfigResponse>().registered == true)
        assertEquals(
            id,
            owner.client.postJson("$API/subscriptions", BrowserPushSubscriptionRequest(ENDPOINT))
                .body<BrowserPushSubscriptionResponse>().id,
        )
        updateSubscriptionCreatedAt(id, System.currentTimeMillis() - 91L * 86_400_000L)
        assertFalse(owner.client.get("$API/config?subscriptionId=$id").body<BrowserPushConfigResponse>().registered == true)
    }

    @Test
    fun `the same new endpoint is serialized across accounts`() = testApplication {
        configureApp(*vapidConfig())
        val first = seedActor("push-race-first").also { users += it.id }
        val second = seedActor("push-race-second").also { users += it.id }
        val responses = coroutineScope {
            listOf(first, second).map { actor ->
                async { actor.client.postJson("$API/subscriptions", BrowserPushSubscriptionRequest(ENDPOINT)) }
            }.awaitAll()
        }
        assertTrue(responses.all { it.status == HttpStatusCode.OK })
        assertEquals(1, endpointOwners().size, "the globally unique endpoint has one serialized owner")
        assertTrue(endpointOwners().single() in setOf(first.id, second.id))
    }

    @Test
    fun `disabled push does not queue notices even when a browser is still registered`() = testApplication {
        usePostgresTestcontainer()
        val cast = seedPairCast("push-disabled", "Push disabled", minted, withThread = true)
        TestNotices.quietRoom(cast.room)
        val id = insertSubscription(cast.ann.id, System.currentTimeMillis())
        assertEquals(HttpStatusCode.Created, cast.owner.client.postJson(
            "/api/v1/rooms/${cast.room}/threads/1/messages",
            ch.nokillswit.threads.MessageRequest("<@${cast.ann.id}> disabled"),
        ).status)
        assertEquals(0, queued(id))
        assertTrue(subscriptionExists(id))
    }

    @Test
    fun `the running notification sweep purges old subscriptions with push disabled`() = testApplication {
        configureApp("notifications.sweepEnabled" to "true")
        val user = TestUsers.seed(uniqueEmail("push-sweep"), "pw").also { users += it }
        val id = insertSubscription(user, System.currentTimeMillis() - 91L * 86_400_000L)
        startApplication()
        withTimeout(60_000) { application.attributes[NotificationSweepKey].await() }
        assertFalse(subscriptionExists(id))
    }

    @Test
    fun `registration is encrypted idempotent caller scoped and detached on logout`() = testApplication {
        val keys = vapidConfig()
        configureApp(*keys)
        val owner = seedActor("push-owner").also { users += it.id }
        val other = seedActor("push-other").also { users += it.id }

        val config = owner.client.get("$API/config").body<BrowserPushConfigResponse>()
        assertTrue(config.available)
        assertEquals(keys.first { it.first.endsWith("publicKey") }.second, config.publicKey)
        val first = owner.client.postJson("$API/subscriptions", BrowserPushSubscriptionRequest(ENDPOINT))
        assertEquals(HttpStatusCode.OK, first.status)
        val id = first.body<BrowserPushSubscriptionResponse>().id
        assertTrue(id > 0u)
        val again = owner.client.postJson("$API/subscriptions", BrowserPushSubscriptionRequest(ENDPOINT))
            .body<BrowserPushSubscriptionResponse>().id
        assertEquals(id, again)
        assertFailsWith<UnauthorizedException> {
            application.attributes[BrowserPushServiceKey].subscribe(owner.id, -1, "$ENDPOINT-revision")
        }

        (2..4).forEach { suffix ->
            assertEquals(HttpStatusCode.OK, owner.client.postJson(
                "$API/subscriptions", BrowserPushSubscriptionRequest("$ENDPOINT-$suffix"),
            ).status)
        }
        val raced = coroutineScope {
            (5..8).map { suffix ->
                async {
                    owner.client.postJson("$API/subscriptions", BrowserPushSubscriptionRequest("$ENDPOINT-$suffix")).status
                }
            }.awaitAll()
        }
        assertEquals(1, raced.count { it == HttpStatusCode.OK }, "the active-user row serializes the five-device cap")
        assertEquals(3, raced.count { it == HttpStatusCode.BadRequest })

        val stored = subscription(id)
        assertNotEquals(ENDPOINT, stored.first)
        assertTrue(stored.first.startsWith("enc:v1:"))
        assertEquals(32, stored.second.size)
        assertEquals(HttpStatusCode.NoContent, other.client.delete("$API/subscriptions/$id").status)
        assertTrue(subscriptionExists(id), "another caller's id is an idempotent no-op")

        val logout = owner.client.post("/api/v1/logout") { header("X-Adder-Push-Subscription", id.toString()) }
        assertEquals(HttpStatusCode.NoContent, logout.status)
        assertFalse(subscriptionExists(id))
    }

    @Test
    fun `enabling push queues only future notices and claim rechecks unseen state`() = testApplication {
        configureApp(*vapidConfig())
        val cast = seedPairCast("push-queue", "Push queue", minted, withThread = true)
        TestNotices.quietRoom(cast.room)
        assertEquals(HttpStatusCode.Created, cast.owner.client.postJson(
            "/api/v1/rooms/${cast.room}/threads/1/messages",
            ch.nokillswit.threads.MessageRequest("<@${cast.ann.id}> before"),
        ).status)
        val id = cast.ann.client.postJson("$API/subscriptions", BrowserPushSubscriptionRequest(ENDPOINT))
            .body<BrowserPushSubscriptionResponse>().id
        assertEquals(0, queued(id), "registration never backfills historical notices")

        assertEquals(HttpStatusCode.Created, cast.owner.client.postJson(
            "/api/v1/rooms/${cast.room}/threads/1/messages",
            ch.nokillswit.threads.MessageRequest("<@${cast.ann.id}> after"),
        ).status)
        assertEquals(1, queued(id))
        val feed = cast.ann.client.get("/api/v1/notifications/feed?seen=false").body<kotlinx.serialization.json.JsonObject>()
        val noticeId = feed["items"]!!.jsonArray.first().jsonObject["id"]!!.jsonPrimitive.content.toUInt()
        assertEquals(HttpStatusCode.NoContent, cast.ann.client.postJson("/api/v1/notifications/$noticeId/seen", "").status)
        val service = application.attributes[BrowserPushServiceKey]
        val claim = service.claim(10, System.currentTimeMillis() - 86_400_000L)
        assertNull(service.eligibleEndpoint(claim.single()), "seen is rechecked immediately before sending")
        assertEquals(0, queued(id), "an invalidated claim is settled rather than retried")
    }

    @Test
    fun `a claimed endpoint is sent once and an expired endpoint is removed`() = testApplication {
        configureApp(*vapidConfig())
        val cast = seedPairCast("push-send", "Push sender", minted, withThread = true)
        TestNotices.quietRoom(cast.room)
        val id = cast.ann.client.postJson("$API/subscriptions", BrowserPushSubscriptionRequest(ENDPOINT))
            .body<BrowserPushSubscriptionResponse>().id
        assertEquals(HttpStatusCode.Created, cast.owner.client.postJson(
            "/api/v1/rooms/${cast.room}/threads/1/messages",
            ch.nokillswit.threads.MessageRequest("<@${cast.ann.id}> queued"),
        ).status)
        val sent = mutableListOf<String>()
        val service = application.attributes[BrowserPushServiceKey]
        val sender = BrowserPushSender(service, PushTransport { endpoint -> sent += endpoint; PushResult.EXPIRED }, 10) {
            System.currentTimeMillis()
        }
        PostgresTestSupport.connect().use { connection ->
            connection.prepareStatement(
                "UPDATE notifications SET created_at = ? WHERE id IN " +
                    "(SELECT notification_id FROM notification_push_queue WHERE subscription_id = ?)",
            ).use { statement ->
                statement.setLong(1, System.currentTimeMillis() - 2 * 86_400_000L)
                statement.setInt(2, id.toInt())
                assertEquals(1, statement.executeUpdate())
            }
        }
        sender.run()
        assertTrue(sent.isEmpty(), "a stale queue entry is settled without an OS notification")
        assertTrue(subscriptionExists(id))
        assertEquals(HttpStatusCode.Created, cast.owner.client.postJson(
            "/api/v1/rooms/${cast.room}/threads/1/messages",
            ch.nokillswit.threads.MessageRequest("<@${cast.ann.id}> fresh"),
        ).status)
        sender.run()
        sender.run()
        assertEquals(listOf(ENDPOINT), sent, "the persisted claim is removed before outbound I/O")
        assertFalse(subscriptionExists(id), "404/410 provider verdicts remove the dead endpoint")
    }

    @Test
    fun `an unexpected device failure does not cancel sibling delivery`() = testApplication {
        configureApp(*vapidConfig())
        val cast = seedPairCast("push-isolated", "Push isolation", minted, withThread = true)
        TestNotices.quietRoom(cast.room)
        val secondEndpoint = "$ENDPOINT-sibling"
        cast.ann.client.postJson("$API/subscriptions", BrowserPushSubscriptionRequest(ENDPOINT))
        cast.ann.client.postJson("$API/subscriptions", BrowserPushSubscriptionRequest(secondEndpoint))
        assertEquals(HttpStatusCode.Created, cast.owner.client.postJson(
            "/api/v1/rooms/${cast.room}/threads/1/messages",
            ch.nokillswit.threads.MessageRequest("<@${cast.ann.id}> both devices"),
        ).status)
        val sent = java.util.Collections.synchronizedList(mutableListOf<String>())
        val service = application.attributes[BrowserPushServiceKey]
        BrowserPushSender(
            service,
            PushTransport { endpoint ->
                if (endpoint == ENDPOINT) error("unexpected transport failure")
                sent += endpoint
                PushResult.DELIVERED
            },
            10,
        ) { System.currentTimeMillis() }.run()
        assertEquals(listOf(secondEndpoint), sent)
    }

    @Test
    fun `configured VAPID public and private keys must match`() = testApplication {
        val first = vapidConfig()
        val second = vapidConfig()
        val mismatched = first.map { pair ->
            if (pair.first.endsWith("privateKey")) pair.first to second.first { it.first.endsWith("privateKey") }.second else pair
        }.toTypedArray()
        configureApp(*mismatched)
        assertStartupFails("does not match") { startApplication() }
    }

    private fun subscription(id: UInt): Pair<String, ByteArray> = PostgresTestSupport.connect().use { connection ->
        connection.prepareStatement(
            "SELECT endpoint_encrypted, endpoint_hash FROM browser_push_subscriptions WHERE id = ?",
        ).use { statement ->
            statement.setInt(1, id.toInt())
            statement.executeQuery().use { rows ->
                assertTrue(rows.next())
                rows.getString(1) to rows.getBytes(2)
            }
        }
    }

    private fun subscriptionExists(id: UInt): Boolean = PostgresTestSupport.connect().use { connection ->
        connection.prepareStatement("SELECT count(*) FROM browser_push_subscriptions WHERE id = ?").use { statement ->
            statement.setInt(1, id.toInt())
            statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) == 1 }
        }
    }

    private fun queued(id: UInt): Int = PostgresTestSupport.connect().use { connection ->
        connection.prepareStatement("SELECT count(*) FROM notification_push_queue WHERE subscription_id = ?").use { statement ->
            statement.setInt(1, id.toInt())
            statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
    }

    private fun updateSubscriptionRevision(id: UInt, revision: Long) = PostgresTestSupport.connect().use { connection ->
        connection.prepareStatement("UPDATE browser_push_subscriptions SET credential_revision = ? WHERE id = ?").use { statement ->
            statement.setLong(1, revision)
            statement.setInt(2, id.toInt())
            assertEquals(1, statement.executeUpdate())
        }
    }

    private fun updateSubscriptionCreatedAt(id: UInt, createdAt: Long) = PostgresTestSupport.connect().use { connection ->
        connection.prepareStatement("UPDATE browser_push_subscriptions SET created_at = ? WHERE id = ?").use { statement ->
            statement.setLong(1, createdAt)
            statement.setInt(2, id.toInt())
            assertEquals(1, statement.executeUpdate())
        }
    }

    private fun endpointOwners(): List<UInt> = PostgresTestSupport.connect().use { connection ->
        connection.prepareStatement("SELECT user_id FROM browser_push_subscriptions").use { statement ->
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getInt(1).toUInt()) } }
        }
    }

    private fun insertSubscription(userId: UInt, createdAt: Long): UInt = PostgresTestSupport.connect().use { connection ->
        connection.prepareStatement(
            "INSERT INTO browser_push_subscriptions " +
                "(user_id, endpoint_hash, endpoint_encrypted, credential_revision, created_at) VALUES (?, ?, ?, 0, ?) RETURNING id",
        ).use { statement ->
            statement.setInt(1, userId.toInt())
            statement.setBytes(2, ByteArray(32) { 1 })
            statement.setString(3, "unused-while-disabled")
            statement.setLong(4, createdAt)
            statement.executeQuery().use { rows -> rows.next(); rows.getInt(1).toUInt() }
        }
    }

    private companion object {
        const val API = "/api/v1/notifications/push"
        const val ENDPOINT = "https://fcm.googleapis.com/fcm/send/test-browser-endpoint"

        fun vapidConfig(): Array<Pair<String, String>> {
            val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            val pair = generator.generateKeyPair()
            val public = pair.public as ECPublicKey
            fun coordinate(value: java.math.BigInteger): ByteArray = value.toByteArray().let { bytes ->
                when {
                    bytes.size == 32 -> bytes
                    bytes.size > 32 -> bytes.copyOfRange(bytes.size - 32, bytes.size)
                    else -> ByteArray(32 - bytes.size) + bytes
                }
            }
            val raw = byteArrayOf(4) + coordinate(public.w.affineX) + coordinate(public.w.affineY)
            val encoder = Base64.getUrlEncoder().withoutPadding()
            return arrayOf(
                "notifications.push.publicKey" to encoder.encodeToString(raw),
                "notifications.push.privateKey" to encoder.encodeToString(pair.private.encoded),
                "notifications.push.subject" to "mailto:push-test@adder.local",
            )
        }
    }
}
