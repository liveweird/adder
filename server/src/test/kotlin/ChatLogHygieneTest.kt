package ch.nokillswit

import ch.nokillswit.threads.MessageResponse
import ch.nokillswit.threads.ThreadResponse
import ch.nokillswit.threads.ThreadSearchPageResponse
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

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

    /**
     * A hashtag is user content like a body: the tag index, the thread list's `tag` filter and the room's tag list (its `tag` substring)
     * must leave the tag in no log line of any logger, whether the request is answered or refused (a malformed filter is a `400` whose
     * detail may echo the value, a response and never a log).
     */
    @Test
    fun `no log line carries a hashtag - written, edited, deleted, filtered or listed`() = testApplication {
        val tag = "hygtag" + UUID.randomUUID().toString().replace("-", "")
        val capture = LogCapture(Logger.ROOT_LOGGER_NAME)
        try {
            usePostgresTestcontainer()
            val cast = castWithThread("hyg-tag")
            val posted = cast.post("""{"body":"P #$tag and #${tag}x"}""")
            assertEquals(HttpStatusCode.Created, posted.status)
            val messageId = posted.body<MessageResponse>().id
            assertEquals(
                HttpStatusCode.NoContent,
                cast.member.client.raw(HttpMethod.Put, "$rooms/${cast.room}/messages/$messageId", """{"body":"E #${tag}y"}""").status,
            )
            val threads = "$rooms/${cast.room}/threads"
            assertEquals(HttpStatusCode.OK, cast.member.client.get("$threads?tag=${tag}y").status)
            assertEquals(HttpStatusCode.OK, cast.member.client.get("$threads?tag=${tag.uppercase()}&tag=nothing").status)
            assertEquals(HttpStatusCode.BadRequest, cast.member.client.get("$threads?tag=%23$tag").status)
            assertEquals(HttpStatusCode.BadRequest, cast.member.client.get("$threads?tag=${tag}${"z".repeat(120)}").status)
            assertEquals(HttpStatusCode.OK, cast.member.client.get("$rooms/${cast.room}/tags?tag=${tag.take(12)}").status)
            assertEquals(HttpStatusCode.BadRequest, cast.member.client.get("$rooms/${cast.room}/tags?tag=${tag}${"z".repeat(60)}").status)
            assertEquals(HttpStatusCode.NoContent, cast.member.client.delete("$rooms/${cast.room}/messages/$messageId").status)

            val names = capture.events.map { it.message }
            assertTrue("message.posted" in names && "message.edited" in names && "message.deleted" in names, "captured: $names")
            val leaked = capture.events.filter { tag in it.rendered() || tag.uppercase() in it.rendered() }
            assertEquals(emptyList(), leaked.map { it.rendered().take(300) }, "a log line carries a hashtag")
        } finally {
            capture.detach()
        }
    }

    /**
     * A search term is user content like a body: the room search must leave it in no log line of any logger, whether the search is
     * answered (200), refused (a control character is a 400 whose detail is a response, never a log) or throttled (the 61st in a minute
     * is the bucket's 429, whose audit line carries the bucket and the user, never the query string). The statement budget's 429 is
     * `ThreadSearchTest`.
     */
    @Test
    fun `no log line carries a search term - answered, refused or throttled`() = testApplication {
        val term = "searchterm" + UUID.randomUUID().toString().replace("-", "")
        val capture = LogCapture(Logger.ROOT_LOGGER_NAME)
        try {
            usePostgresTestcontainer()
            val cast = castWithThread("hyg-srch")
            assertEquals(HttpStatusCode.Created, cast.post("""{"body":"P $term here"}""").status)
            val search = "$rooms/${cast.room}/search"
            val answered = cast.member.client.get("$search?q=$term")
            assertEquals(HttpStatusCode.OK, answered.status)
            assertEquals(1, answered.body<ThreadSearchPageResponse>().total, "the search really found the message")
            assertEquals(HttpStatusCode.BadRequest, cast.member.client.get("$search?q=$term%07").status)
            assertEquals(HttpStatusCode.BadRequest, cast.member.client.get("$search?q=$term&q=other").status)
            assertEquals(HttpStatusCode.OK, cast.member.client.get("$search?q=${term.uppercase()}%20and%20!!!").status)
            repeat(56) { cast.member.client.get("$search?q=$term") }
            assertEquals(HttpStatusCode.TooManyRequests, cast.member.client.get("$search?q=$term").status)

            val names = capture.events.map { it.message }
            assertTrue("message.posted" in names && "rate_limit.exceeded" in names, "captured: $names")
            val leaked = capture.events.filter { term in it.rendered() || term.uppercase() in it.rendered() }
            assertEquals(emptyList(), leaked.map { it.rendered().take(300) }, "a log line carries a search term")
        } finally {
            capture.detach()
        }
    }

    private class Cast(val member: RoomActor, val room: UInt)

    /** A room with an owner, a member and one thread (number 1) for the post. */
    private suspend fun ApplicationTestBuilder.castWithThread(prefix: String): Cast {
        val owner = seedActor("$prefix-own").also { mintedUsers += it.id }
        val member = seedActor("$prefix-mem").also { mintedUsers += it.id }
        val template = TestThreads.seedTemplate().also { mintedTemplates += it }
        val room = TestRooms.seed(owner.id, template, "Hyg ${UUID.randomUUID().toString().substring(0, 8)}").also { mintedRooms += it }
        TestRooms.addMember(room, member.id)
        val created = member.client.raw(HttpMethod.Post, "$rooms/$room/threads", """{"title":"T","body":"B","fields":{"severity":"s1"}}""")
        assertEquals(HttpStatusCode.Created, created.status)
        return Cast(member, room)
    }

    /** The 500 lines of the capture: what the catch-all logged for a failed request. */
    private fun LogCapture.failureLines(): List<ILoggingEvent> =
        events.filter { it.message.startsWith("Unhandled exception while processing") }

    private suspend fun Cast.post(body: String): HttpResponse = member.client.raw(HttpMethod.Post, "$rooms/$room/threads/1/messages", body)

    @Test
    fun `the 500 line names the driver class and 57014`() = testApplication {
        val marker = "HYGIENE-${UUID.randomUUID()}"
        val capture = LogCapture(Logger.ROOT_LOGGER_NAME)
        try {
            usePostgresTestcontainer()
            val cast = castWithThread("hyg-cancel")

            // A SHAPE pin, not a leak proof (the default message does not expand arguments, the echo test below proves the leak): the
            // post passes every check, its INSERT waits behind a table lock, and cancelling that backend is a database failure (57014)
            // whose 500 line must still name the driver's class and the SQLSTATE after the scrubbing.
            val status = PostgresTestSupport.connect().use { holder ->
                holder.autoCommit = false
                holder.createStatement().use { it.execute("LOCK TABLE messages IN ACCESS EXCLUSIVE MODE") }
                try {
                    coroutineScope {
                        val posting = async { cast.post("""{"body":"P $marker-post"}""") }
                        awaitLockWait("INSERT INTO messages")
                        cancelBackends("INSERT INTO messages")
                        posting.await().status
                    }
                } finally {
                    holder.rollback()
                }
            }
            assertEquals(HttpStatusCode.InternalServerError, status)

            val failure = capture.failureLines().map { it.rendered() }
            assertTrue(failure.isNotEmpty(), "the 500 was logged")
            assertTrue(failure.all { "57014" in it }, "the line still names the SQLSTATE")
            assertTrue(failure.all { "PostgresqlNonTransientResourceException" in it }, "the line still names the driver's exception class")
            val leaked = capture.events.filter { marker in it.rendered() }
            assertEquals(emptyList(), leaked.map { it.rendered().take(300) }, "a failed statement logged its bound values")
        } finally {
            capture.detach()
        }
    }

    /**
     * Postgres itself can echo a value into an error message (an input-syntax failure, a `RAISE`, a malformed tsquery), and the driver
     * carries that message in its exception: the catch-all must not render it. A trigger that rejects ONLY this test's marker stands in
     * for such a failure, so the message is raised from the database with the bound body in its text.
     */
    @Test
    fun `a database error whose own message echoes the body answers 500 and leaves the body in no log line`() = testApplication {
        val marker = "HYGIENE-${UUID.randomUUID()}"
        val capture = LogCapture(Logger.ROOT_LOGGER_NAME)
        try {
            usePostgresTestcontainer()
            val cast = castWithThread("hyg-echo")
            PostgresTestSupport.connect().use { admin ->
                admin.createStatement().use { st ->
                    st.execute(
                        "CREATE OR REPLACE FUNCTION hyg_reject() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN " +
                            "IF position('$marker' in NEW.body) > 0 THEN RAISE EXCEPTION 'rejected body %', NEW.body; " +
                            "END IF; RETURN NEW; END \$\$",
                    )
                    st.execute("CREATE TRIGGER hyg_reject_trg BEFORE INSERT ON messages FOR EACH ROW EXECUTE FUNCTION hyg_reject()")
                }
            }
            try {
                val posted = cast.post("""{"body":"P $marker-post"}""")
                assertEquals(HttpStatusCode.InternalServerError, posted.status)
            } finally {
                PostgresTestSupport.connect().use { admin ->
                    admin.createStatement().use { st ->
                        st.execute("DROP TRIGGER IF EXISTS hyg_reject_trg ON messages")
                        st.execute("DROP FUNCTION IF EXISTS hyg_reject()")
                    }
                }
            }

            val failure = capture.failureLines().map { it.rendered() }
            assertTrue(failure.isNotEmpty(), "the 500 was logged")
            assertTrue(failure.all { "P0001" in it }, "the line still names the SQLSTATE")
            val leaked = capture.events.filter { marker in it.rendered() }
            assertEquals(emptyList(), leaked.map { it.rendered().take(300) }, "a database error message reached a log line")
        } finally {
            capture.detach()
        }
    }

    /**
     * Two loggers print a failure's message below INFO (Exposed's retry DEBUG line `Transaction attempt #N failed: ${cause.message}`,
     * StatusPages' TRACE line with the URI and `cause.toString()`); `logback.xml` pins both so a root-level change cannot switch them on.
     */
    @Test
    fun `the loggers that print a failure's message below INFO are pinned to INFO`() {
        listOf("Exposed", "io.ktor.server.plugins.statuspages").forEach { name ->
            assertEquals(Level.INFO, (LoggerFactory.getLogger(name) as Logger).level, name)
        }
    }

    /** `pg_cancel_backend` for every session currently waiting on a lock whose query contains [queryFragment]. */
    private fun cancelBackends(queryFragment: String) {
        PostgresTestSupport.connect().use { probe ->
            probe.prepareStatement(
                "SELECT pg_cancel_backend(pid) FROM pg_stat_activity " +
                    "WHERE wait_event_type = 'Lock' AND pid <> pg_backend_pid() AND query ILIKE ?",
            ).use { st ->
                st.setString(1, "%$queryFragment%")
                st.executeQuery().close()
            }
        }
    }
}
