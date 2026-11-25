package ch.nokillswit

import ch.nokillswit.authz.RoomAccessType
import ch.nokillswit.infra.db.PreviewFetcherKey
import ch.nokillswit.infra.outbound.BlockedPreviewUrlException
import ch.nokillswit.infra.outbound.PreviewContent
import ch.nokillswit.infra.outbound.PreviewFetcher
import ch.nokillswit.infra.outbound.PreviewImage
import ch.nokillswit.infra.outbound.PreviewKind
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.threads.LinkPreviewKind
import ch.nokillswit.threads.LinkPreviewRequest
import ch.nokillswit.threads.LinkPreviewResponse
import ch.nokillswit.threads.MessageRequest
import ch.nokillswit.threads.messageLinkDestinations
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking

class MessageLinkPreviewTest {
    private val minted = MintedIds(mutableListOf(), mutableListOf(), mutableListOf())

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(minted.rooms)
        minted.users.forEach { TestUsers.softDelete(it) }
        minted.templates.forEach { TestTemplates.delete(it) }
    }

    @Test
    fun `extracts rendered links images references and autolinks with browser-style canonical URLs`() {
        val markdown = """
            [page](https://EXAMPLE.com)
            ![diagram](https://example.com/a%20b.png)
            [reference][docs]
            <https://example.com/angle?q=one>
            bare https://example.com/bare
            [fragment](https://example.com/page#part)

            [docs]: https://bücher.example/guide
        """.trimIndent()

        assertEquals(
            setOf(
                "https://example.com/",
                "https://example.com/a%20b.png",
                "https://xn--bcher-kva.example/guide",
                "https://example.com/angle?q=one",
                "https://example.com/bare",
                "https://example.com/page#part",
            ),
            messageLinkDestinations(markdown),
        )
    }

    @Test
    fun `canonical destinations must remain bounded and obey URI whitespace grammar`() {
        val expanding = "https://example.com/" + "é".repeat(400)
        assertTrue(expanding.length <= 2_048)
        assertTrue(messageLinkDestinations("[expands]($expanding)").isEmpty(), "percent encoding must not exceed the wire bound")
        assertTrue(messageLinkDestinations("[space](<https://example.com/a b>)").isEmpty())
        assertTrue(messageLinkDestinations("[tab](<https://example.com/a\tb>)").isEmpty())
        assertEquals(
            setOf("https://example.com/a%20b#part"),
            messageLinkDestinations("[encoded](https://example.com/a%20b#part)"),
        )
    }

    @Test
    fun `ignores code raw HTML email and plain text while keeping distinct image and linked-image destinations`() {
        val markdown = """
            `https://example.com/inline`

            ```
            https://example.com/fenced
            ```

            <a href="https://example.com/raw">raw</a>
            <person@example.com>
            example.com/plain
            [![Chart](https://cdn.example.com/chart.jpg)](https://example.com/report)
        """.trimIndent()

        assertEquals(
            setOf("https://cdn.example.com/chart.jpg", "https://example.com/report"),
            messageLinkDestinations(markdown),
        )
    }

    @Test
    fun `a current link returns a bounded no-store preview while unsupported http is uniformly unavailable`() = testApplication {
        val fetcher = FakePreviewFetcher { url ->
            when (url) {
                "https://docs.example.com/guide" -> PreviewContent(
                    PreviewKind.PAGE,
                    finalUrl = url,
                    title = "Guide",
                    image = PreviewImage("image/png", "AA==", 1, 1),
                )
                "https://cdn.example.com/chart.jpg" -> PreviewContent(PreviewKind.IMAGE, url, null, null)
                "https://example.com/down" -> null
                "https://example.com/blocked?token=secret#private" -> throw BlockedPreviewUrlException()
                "https://example.com/defensive" -> PreviewContent(PreviewKind.UNAVAILABLE, url, "ignored", null)
                "https://example.com/thumbnail?token=thumbnail-secret" -> PreviewContent(
                    PreviewKind.PAGE, url, "Title survives", null, blockedThumbnail = true,
                )
                else -> error("unexpected URL")
            }
        }
        usePostgresWith(fetcher)
        val cast = seedThreadCast("preview-ok", "Preview", minted)
        val message = TestThreads.post(
            cast.room,
            1,
            cast.author.id,
            """
                Read [the guide](https://DOCS.example.com/guide), ![chart](https://cdn.example.com/chart.jpg),
                [down](https://example.com/down), [blocked](https://example.com/blocked?token=secret#private),
                [defensive](https://example.com/defensive),
                [thumbnail](https://example.com/thumbnail?token=thumbnail-secret)
                and <http://legacy.example.com/page>.
            """.trimIndent(),
        )

        val response = cast.member.client.postJson(
            previewPath(cast.room, message),
            LinkPreviewRequest("https://docs.example.com/guide"),
        )
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals(
            LinkPreviewResponse(
                LinkPreviewKind.PAGE,
                "https://docs.example.com/guide",
                "https://docs.example.com/guide",
                "Guide",
                ch.nokillswit.threads.LinkPreviewImageResponse("image/png", "AA==", 1, 1),
            ),
            response.body<LinkPreviewResponse>(),
        )
        assertEquals(listOf("https://docs.example.com/guide"), fetcher.urls)

        val image = cast.member.client.postJson(
            previewPath(cast.room, message),
            LinkPreviewRequest("https://cdn.example.com/chart.jpg"),
        ).body<LinkPreviewResponse>()
        assertEquals(LinkPreviewKind.IMAGE, image.kind)
        assertEquals("https://cdn.example.com/chart.jpg", image.finalUrl)

        for (url in listOf("https://example.com/down", "https://example.com/defensive")) {
            val unavailable = cast.member.client.postJson(previewPath(cast.room, message), LinkPreviewRequest(url))
                .body<LinkPreviewResponse>()
            assertEquals(LinkPreviewKind.UNAVAILABLE, unavailable.kind, url)
            assertNull(unavailable.finalUrl)
            assertNull(unavailable.title)
            assertNull(unavailable.image)
        }

        withAuditCapture { capture ->
            repeat(2) {
                val blocked = cast.member.client.postJson(
                    previewPath(cast.room, message), LinkPreviewRequest("https://example.com/blocked?token=secret#private"),
                ).body<LinkPreviewResponse>()
                assertEquals(LinkPreviewKind.UNAVAILABLE, blocked.kind)
            }
            val blockedEvents = capture.events.filter { it.message == "outbound.blocked" }
            assertEquals(1, blockedEvents.size, "repeated policy refusals are coalesced")
            val event = blockedEvents.single()
            assertTrue(event.hasKeyValue("byUserId", cast.member.id.toLong()))
            assertTrue(event.hasKeyValue("roomId", cast.room.toLong()))
            assertTrue(event.hasKeyValue("messageId", message.toLong()))
            assertTrue(event.hasKeyValue("stage", "primary"))
            assertTrue(event.hasKeyValue("suppressed", 0L))
            assertTrue(event.keyValuePairs.none { "example.com" in it.value.toString() }, "the audit carries no URL")
            assertTrue(event.keyValuePairs.none { "secret" in it.value.toString() }, "the audit carries no query or fragment")
        }

        withAuditCapture { capture ->
            val page = cast.member.client.postJson(
                previewPath(cast.room, message), LinkPreviewRequest("https://example.com/thumbnail?token=thumbnail-secret"),
            ).body<LinkPreviewResponse>()
            assertEquals(LinkPreviewKind.PAGE, page.kind)
            assertEquals("Title survives", page.title, "a blocked optional thumbnail does not discard safe page metadata")
            val event = capture.events.single { it.message == "outbound.blocked" && it.hasKeyValue("stage", "thumbnail") }
            assertTrue(event.keyValuePairs.none { "secret" in it.value.toString() }, "optional-target details stay out of the audit")
        }

        val unavailable = cast.member.client.postJson(
            previewPath(cast.room, message),
            LinkPreviewRequest("http://legacy.example.com/page"),
        ).body<LinkPreviewResponse>()
        assertEquals(LinkPreviewKind.UNAVAILABLE, unavailable.kind)
        assertNull(unavailable.finalUrl)
        assertNull(unavailable.title)
        assertNull(unavailable.image)
        assertEquals(7, fetcher.urls.size, "plain HTTP is never handed to the outbound transport")
    }

    @Test
    fun `room and message guards precede JSON and only exact current rendered destinations are accepted`() = testApplication {
        val fetcher = FakePreviewFetcher { error("a refused preview must not fetch") }
        usePostgresWith(fetcher)
        val cast = seedThreadCast("preview-guard", "Preview", minted)
        val message = TestThreads.post(
            cast.room,
            1,
            cast.author.id,
            "[Allowed][target]\n\n[target]: https://example.com/current",
        )

        assertEquals(
            HttpStatusCode.NotFound,
            cast.outsider.client.postMalformed(previewPath(cast.room, message)).status,
            "visibility is checked before JSON",
        )
        assertEquals(
            HttpStatusCode.NotFound,
            cast.member.client.postMalformed(previewPath(cast.room, Int.MAX_VALUE.toUInt())).status,
            "message parentage is checked before JSON",
        )
        val oversized = """{"url":"https://example.com/current","padding":"${"x".repeat(17 * 1_024)}"}"""
        assertEquals(HttpStatusCode.PayloadTooLarge, cast.member.client.postRaw(previewPath(cast.room, message), oversized).status)
        assertEquals(
            HttpStatusCode.PayloadTooLarge,
            cast.outsider.client.postRaw(previewPath(cast.room, message), oversized).status,
            "the route's 16 KiB memory ceiling runs before authorization",
        )
        val foreign = cast.member.client.postJson(
            previewPath(cast.room, message),
            LinkPreviewRequest("https://example.com/not-current"),
        )
        assertEquals(HttpStatusCode.BadRequest, foreign.status)
        assertEquals("url must identify a current link in the message", foreign.body<ProblemDetail>().detail)
        val expanding = "https://example.com/" + "é".repeat(400)
        for (url in listOf("", "not a URL", " https://example.com/current", expanding, "https://example.com/" + "x".repeat(2_049))) {
            val refused = cast.member.client.postJson(previewPath(cast.room, message), LinkPreviewRequest(url))
            assertEquals(
                HttpStatusCode.BadRequest,
                refused.status,
                url.take(20),
            )
            assertEquals("url must identify a current link in the message", refused.body<ProblemDetail>().detail)
        }
        assertTrue(fetcher.urls.isEmpty())
    }

    @Test
    fun `an admin outside a visible room may preview but a hidden room remains undiscoverable`() = testApplication {
        val fetcher = FakePreviewFetcher { null }
        usePostgresWith(fetcher)
        val cast = seedThreadCast("preview-admin", "Preview", minted)
        val visibleMessage = TestThreads.post(cast.room, 1, cast.author.id, "[Visible](https://example.com/visible)")

        assertEquals(
            HttpStatusCode.OK,
            cast.admin.client.postJson(
                previewPath(cast.room, visibleMessage), LinkPreviewRequest("https://example.com/visible"),
            ).status,
        )

        val hidden = TestRooms.seed(
            cast.owner.id,
            minted.templates.last(),
            "Hidden preview ${cast.room}",
            accessType = RoomAccessType.RESTRICTED_HIDDEN,
        ).also { minted.rooms += it }
        val hiddenMessage = TestThreads.seed(
            hidden, cast.owner.id, body = "[Hidden](https://example.com/hidden)",
        ).messageId
        assertEquals(
            HttpStatusCode.NotFound,
            cast.admin.client.postJson(
                previewPath(hidden, hiddenMessage), LinkPreviewRequest("https://example.com/hidden"),
            ).status,
        )
        assertEquals(listOf("https://example.com/visible"), fetcher.urls, "a hidden-room denial never reaches transport")
    }

    @Test
    fun `membership revocation and message deletion during fetch suppress the late result`() = testApplication {
        val memberUrl = "https://example.com/member"
        val deletedUrl = "https://example.com/deleted"
        val entered = mapOf(memberUrl to CompletableDeferred<Unit>(), deletedUrl to CompletableDeferred<Unit>())
        val release = mapOf(memberUrl to CompletableDeferred<Unit>(), deletedUrl to CompletableDeferred<Unit>())
        val fetcher = FakePreviewFetcher { url ->
            entered.getValue(url).complete(Unit)
            release.getValue(url).await()
            PreviewContent(PreviewKind.PAGE, url, "Must not escape", null)
        }
        usePostgresWith(fetcher)
        val cast = seedThreadCast("preview-revoke", "Preview", minted)
        val memberMessage = TestThreads.post(cast.room, 1, cast.author.id, "[Member]($memberUrl)")
        val deletedMessage = TestThreads.post(cast.room, 1, cast.author.id, "[Deleted]($deletedUrl)")

        coroutineScope {
            val revoked = async {
                cast.member.client.postJson(previewPath(cast.room, memberMessage), LinkPreviewRequest(memberUrl))
            }
            entered.getValue(memberUrl).await()
            assertEquals(
                HttpStatusCode.NoContent,
                cast.owner.client.delete("/api/v1/rooms/${cast.room}/members/${cast.member.id}").status,
            )
            release.getValue(memberUrl).complete(Unit)
            assertEquals(HttpStatusCode.NotFound, revoked.await().status, "the post-fetch room recheck sees lost membership")

            val deleted = async {
                cast.author.client.postJson(previewPath(cast.room, deletedMessage), LinkPreviewRequest(deletedUrl))
            }
            entered.getValue(deletedUrl).await()
            assertEquals(
                HttpStatusCode.NoContent,
                cast.owner.client.delete("/api/v1/rooms/${cast.room}/messages/$deletedMessage").status,
            )
            release.getValue(deletedUrl).complete(Unit)
            assertEquals(HttpStatusCode.NotFound, deleted.await().status, "the post-fetch message recheck sees deletion")
        }
        assertEquals(listOf(memberUrl, deletedUrl), fetcher.urls)
    }

    @Test
    fun `only one preview runs per caller and an edited-away link cannot return a late result`() = testApplication {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fetcher = FakePreviewFetcher {
            entered.complete(Unit)
            release.await()
            PreviewContent(PreviewKind.PAGE, it, "Old title", null)
        }
        usePostgresWith(fetcher)
        val cast = seedThreadCast("preview-race", "Preview", minted)
        val message = TestThreads.post(cast.room, 1, cast.author.id, "[Old](https://example.com/old)")

        coroutineScope {
            val first = async {
                cast.author.client.postJson(
                    previewPath(cast.room, message),
                    LinkPreviewRequest("https://example.com/old"),
                )
            }
            entered.await()
            val concurrent = cast.author.client.postJson(
                previewPath(cast.room, message),
                LinkPreviewRequest("https://example.com/old"),
            )
            assertEquals(HttpStatusCode.TooManyRequests, concurrent.status)
            assertEquals("1", concurrent.headers[HttpHeaders.RetryAfter])

            assertEquals(
                HttpStatusCode.NoContent,
                cast.author.client.putJson(
                    "/api/v1/rooms/${cast.room}/messages/$message",
                    MessageRequest("The link was removed"),
                ).status,
            )
            release.complete(Unit)
            assertEquals(HttpStatusCode.BadRequest, first.await().status, "the post-fetch message recheck refuses stale content")
        }
        assertEquals(1, fetcher.urls.size, "the rejected concurrent call never reaches transport")
    }

    @Test
    fun `the outbound-work bucket permits twenty previews per user and isolates callers`() = testApplication {
        val fetcher = FakePreviewFetcher { null }
        usePostgresWith(fetcher)
        val cast = seedThreadCast("preview-limit", "Preview", minted)
        val message = TestThreads.post(cast.room, 1, cast.author.id, "[Guide](https://example.com/guide)")
        val path = previewPath(cast.room, message)
        val request = LinkPreviewRequest("https://example.com/guide")

        repeat(20) { index ->
            assertEquals(HttpStatusCode.OK, cast.member.client.postJson(path, request).status, "request ${index + 1}")
        }
        assertEquals(HttpStatusCode.TooManyRequests, cast.member.client.postJson(path, request).status)
        assertEquals(HttpStatusCode.OK, cast.author.client.postJson(path, request).status, "another user's bucket is independent")
        assertEquals(21, fetcher.urls.size)
    }

    private suspend fun ApplicationTestBuilder.usePostgresWith(fetcher: PreviewFetcher) {
        configureApp()
        application { attributes.put(PreviewFetcherKey, fetcher) }
        startApplication()
    }

    private fun previewPath(roomId: UInt, messageId: UInt) =
        "/api/v1/rooms/$roomId/messages/$messageId/link-preview"

    private suspend fun io.ktor.client.HttpClient.postMalformed(path: String) = post(path) {
        contentType(ContentType.Application.Json)
        setBody("{ not json")
    }

    private suspend fun io.ktor.client.HttpClient.postRaw(path: String, json: String) = post(path) {
        contentType(ContentType.Application.Json)
        setBody(json)
    }

    private class FakePreviewFetcher(
        private val answer: suspend (String) -> PreviewContent?,
    ) : PreviewFetcher {
        val urls = CopyOnWriteArrayList<String>()

        override suspend fun fetch(url: String): PreviewContent? {
            urls += url
            return answer(url)
        }
    }
}
