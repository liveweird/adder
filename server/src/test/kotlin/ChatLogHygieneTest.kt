package ch.nokillswit

import ch.nokillswit.threads.MessageResponse
import ch.nokillswit.threads.ThreadResponse
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Application-log hygiene of the chat writes (the 0.3.0 security audit): a message body, a thread title and a TEXT field value are
 * user content that may hold anything (a credential pasted into an incident chat), so NO log line of ANY logger - the root logger
 * collects all of them, the audit trail, Ktor, the pool and the driver included - may carry them, whether the write succeeds or is
 * refused. The capture renders every part of an event (message, arguments, key/values, MDC, the throwable with its cause chain) and
 * looks for a marker string that was planted in the body, the title and the field. It watches the levels the application is
 * configured to log at (INFO): the DEBUG statement logs of the database driver are off by design and would bind every parameter.
 */
class ChatLogHygieneTest {
    private val rooms = "/api/v1/rooms"
    private val mintedRooms = mutableListOf<UInt>()
    private val mintedUsers = mutableListOf<UInt>()
    private val mintedTemplates = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(mintedRooms)
        mintedUsers.forEach { TestUsers.softDelete(it) }
        mintedTemplates.forEach { TestTemplates.delete(it) }
    }

    /** Everything an event can carry, as one string. */
    private fun ILoggingEvent.rendered(): String = buildString {
        append(loggerName).append(' ').append(formattedMessage).append(' ').append(message)
        argumentArray?.forEach { append(' ').append(it) }
        keyValuePairs?.forEach { append(' ').append(it.key).append('=').append(it.value) }
        mdcPropertyMap?.forEach { (key, value) -> append(' ').append(key).append('=').append(value) }
        markerList?.forEach { append(' ').append(it.name) }
        throwableProxy?.let { append(' ').append(ThrowableProxyUtil.asString(it)) }
    }

    private suspend fun HttpClient.raw(method: HttpMethod, path: String, json: String, vararg headers: Pair<String, String>): HttpResponse =
        request(path) {
            this.method = method
            headers.forEach { (name, value) -> this.headers.append(name, value) }
            contentType(ContentType.Application.Json)
            setBody(json)
        }

    @Test
    fun `no log line of any logger carries a message body, a thread title or a field value - written or refused`() = testApplication {
        val marker = "HYGIENE-${UUID.randomUUID()}"
        val capture = LogCapture(Logger.ROOT_LOGGER_NAME)
        try {
            usePostgresTestcontainer()
            val owner = seedActor("hyg-own").also { mintedUsers += it.id }
            val member = seedActor("hyg-mem").also { mintedUsers += it.id }
            val template = TestThreads.seedTemplate().also { mintedTemplates += it }
            val room = TestRooms.seed(owner.id, template, "Hyg ${UUID.randomUUID().toString().substring(0, 8)}").also { mintedRooms += it }
            TestRooms.addMember(room, member.id)

            // A thread: the title, the opening body and a TEXT field all carry the marker.
            val create = member.client.raw(
                HttpMethod.Post, "$rooms/$room/threads",
                """{"title":"T $marker-title","body":"B $marker-body","fields":{"severity":"s1","note":"N $marker-note"}}""",
            )
            assertEquals(HttpStatusCode.Created, create.status)
            val thread = create.body<ThreadResponse>()

            // A message: posted, edited, deleted.
            val posted = member.client.raw(HttpMethod.Post, "$rooms/$room/threads/1/messages", """{"body":"P $marker-post"}""")
            assertEquals(HttpStatusCode.Created, posted.status)
            val messageId = posted.body<MessageResponse>().id
            assertEquals(
                HttpStatusCode.NoContent,
                member.client.raw(HttpMethod.Put, "$rooms/$room/messages/$messageId", """{"body":"E $marker-edit"}""").status,
            )
            // The details PUT rewrites the title and the field.
            assertEquals(
                HttpStatusCode.NoContent,
                member.client.raw(
                    HttpMethod.Put, "$rooms/$room/threads/1",
                    """{"title":"U $marker-utitle","fields":{"note":"N2 $marker-note2"}}""",
                    "X-Expected-Revision" to thread.revision.toString(),
                ).status,
            )
            assertEquals(HttpStatusCode.NoContent, owner.client.delete("$rooms/$room/messages/$messageId").status)

            // Refusals carry the marker too: a control character, a nested field value, an unknown field key, an oversize body, a stale
            // revision.
            val refused = listOf(
                member.client.raw(HttpMethod.Post, "$rooms/$room/threads/1/messages", """{"body":"bad\u0007 $marker-ctl"}"""),
                member.client.raw(
                    HttpMethod.Post, "$rooms/$room/threads",
                    """{"title":"T","body":"B","fields":{"note":{"$marker-nest":1}}}""",
                ),
                member.client.raw(HttpMethod.Post, "$rooms/$room/threads", """{"title":"T","body":"B","fields":{"$marker-key":"v"}}"""),
                member.client.raw(HttpMethod.Post, "$rooms/$room/threads/1/messages", """{"body":"$marker-big${"x".repeat(70 * 1024)}"}"""),
                member.client.raw(
                    HttpMethod.Put, "$rooms/$room/threads/1", """{"title":"S $marker-stale","fields":{}}""",
                    "X-Expected-Revision" to "1",
                ),
            )
            assertEquals(
                listOf(400, 400, 400, 413, 409),
                refused.map { it.status.value },
            )

            // The capture really saw this traffic (the audit lines reach the root logger), or "no marker" would prove nothing.
            val names = capture.events.map { it.message }
            assertTrue("thread.created" in names && "message.posted" in names && "message.deleted" in names, "captured: $names")
            val leaked = capture.events.filter { marker in it.rendered() }
            assertEquals(emptyList(), leaked.map { it.rendered().take(300) }, "a log line carries user content")
        } finally {
            capture.detach()
        }
    }
}
