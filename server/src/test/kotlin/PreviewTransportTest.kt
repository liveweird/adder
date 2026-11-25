package ch.nokillswit

import ch.nokillswit.infra.outbound.BlockedPreviewUrlException
import ch.nokillswit.infra.outbound.MAX_PREVIEW_URL_LENGTH
import ch.nokillswit.infra.outbound.DirectPreviewSocketFactory
import ch.nokillswit.infra.outbound.PreviewKind
import ch.nokillswit.infra.outbound.PublicLinkPreviewFetcher
import ch.nokillswit.infra.outbound.ValidatedPreviewTarget
import ch.nokillswit.infra.outbound.absoluteDnsName
import ch.nokillswit.infra.outbound.resolvePreviewTarget
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl

class PreviewTransportTest {
    private val publicIpv4 = InetAddress.getByName("8.8.8.8")

    @Test
    fun `accepts one canonical public https origin and pins its IPv4 snapshot`() {
        var resolvedHost: String? = null
        val target = resolvePreviewTarget(" https://Example.COM/path?q=1#section ") {
            resolvedHost = it
            listOf(publicIpv4)
        }

        assertEquals("example.com", target.url.host)
        assertEquals(443, target.url.port)
        assertEquals(listOf(publicIpv4), target.addresses)
        assertEquals("example.com", resolvedHost)
        assertEquals("example.com.", absoluteDnsName("example.com."))
    }

    @Test
    fun `rejects every URL form that can escape the public https boundary`() {
        val invalid = listOf(
            "not a url",
            "http://example.com/",
            "https://example.com:8443/",
            "https://user:secret@example.com/",
            "https://127.0.0.1/",
            "https://[2606:4700:4700::1111]/",
            "https://localhost/",
            "https://com/",
            "https://service.local/",
            "https://router.home.arpa/",
            "https://reserved.example/",
            "https://hidden.onion/",
            "https://example.com/${"x".repeat(MAX_PREVIEW_URL_LENGTH)}",
        )

        invalid.forEach { raw ->
            assertFailsWith<BlockedPreviewUrlException>(raw) {
                resolvePreviewTarget(raw) { listOf(publicIpv4) }
            }
        }
    }

    @Test
    fun `rejects non-global IPv4 and an answer without public IPv4`() {
        val blocked = listOf(
            "0.0.0.0", "10.0.0.1", "100.64.0.1", "127.0.0.1", "169.254.169.254",
            "172.16.0.1", "192.0.0.1", "192.0.2.1", "192.168.0.1", "198.18.0.1",
            "192.31.196.1", "192.52.193.1", "192.88.99.1", "192.175.48.1",
            "198.51.100.1", "203.0.113.1", "224.0.0.1", "240.0.0.1", "255.255.255.255",
        )

        blocked.forEach { address ->
            assertFailsWith<BlockedPreviewUrlException>(address) {
                resolvePreviewTarget("https://example.com/") { listOf(InetAddress.getByName(address)) }
            }
        }
        assertFailsWith<BlockedPreviewUrlException> {
            resolvePreviewTarget("https://example.com/") {
                listOf(publicIpv4, InetAddress.getByName("127.0.0.1"))
            }
        }
        assertFailsWith<BlockedPreviewUrlException> {
            resolvePreviewTarget("https://example.com/") { listOf(InetAddress.getByName("2606:4700:4700::1111")) }
        }
    }

    @Test
    fun `drops public IPv6 from a dual-stack answer while retaining vetted IPv4`() {
        val target = resolvePreviewTarget("https://example.com/") {
            listOf(InetAddress.getByName("2606:4700:4700::1111"), publicIpv4)
        }

        assertEquals(listOf(publicIpv4), target.addresses)
    }

    @Test
    fun `rejects an empty or failed DNS result uniformly`() {
        assertFailsWith<BlockedPreviewUrlException> {
            resolvePreviewTarget("https://example.com/") { emptyList() }
        }
        assertFailsWith<BlockedPreviewUrlException> {
            resolvePreviewTarget("https://example.com/") { throw java.net.UnknownHostException() }
        }
    }

    @Test
    fun fetchesHtmlThroughValidatedRedirectsWithoutAmbientCredentials() {
        withServer { server ->
            val requests = mutableListOf<String>()
            for (path in listOf("/one", "/two", "/three")) {
                server.createContext(path) { exchange ->
                    synchronized(requests) {
                        requests += exchange.requestHeaders.entries.joinToString { (key, value) -> key + "=" + value.joinToString() }
                    }
                    val next = when (path) {
                        "/one" -> "/two"
                        "/two" -> "/three"
                        else -> "/final"
                    }
                    exchange.responseHeaders.add("Location", next)
                    exchange.sendResponseHeaders(302, -1)
                    exchange.close()
                }
            }
            server.createContext("/final") { exchange ->
                synchronized(requests) {
                    requests += exchange.requestHeaders.entries.joinToString { (key, value) -> key + "=" + value.joinToString() }
                }
                val body = "<title>Safe preview</title>".toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
            val resolutions = AtomicInteger()
            val fetcher = fixtureFetcher { resolutions.incrementAndGet() }
            val result = runBlocking { fetcher.fetch(base(server) + "/one") }
            fetcher.close()

            assertEquals(PreviewKind.PAGE, result?.kind)
            assertEquals("Safe preview", result?.title)
            assertEquals(4, resolutions.get())
            assertEquals(4, requests.size)
            requests.forEach { headers ->
                assertEquals(true, headers.contains("Accept-encoding=identity", ignoreCase = true))
                assertEquals(false, headers.contains("Authorization", ignoreCase = true))
                assertEquals(false, headers.contains("Cookie", ignoreCase = true))
                assertEquals(false, headers.contains("Referer", ignoreCase = true))
            }
        }
    }

    @Test
    fun revalidatesRedirectDestinationsAndRefusesAFourthRedirect() {
        withServer { server ->
            for (index in 0..4) {
                server.createContext("/" + index) { exchange ->
                    exchange.responseHeaders.add("Location", "/" + (index + 1))
                    exchange.sendResponseHeaders(302, -1)
                    exchange.close()
                }
            }
            server.createContext("/blocked") { exchange ->
                exchange.responseHeaders.add("Location", "https://127.0.0.1/private")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            server.start()
            val localBase = base(server)
            val fetcher = PublicLinkPreviewFetcher(targetResolver = { raw ->
                if (raw.startsWith(localBase)) {
                    ValidatedPreviewTarget(raw.toHttpUrl(), listOf(InetAddress.getLoopbackAddress()))
                } else {
                    resolvePreviewTarget(raw) { listOf(InetAddress.getLoopbackAddress()) }
                }
            })
            assertNull(runBlocking { fetcher.fetch(localBase + "/0") })
            assertFailsWith<BlockedPreviewUrlException> { runBlocking { fetcher.fetch(localBase + "/blocked") } }
            fetcher.close()
        }
    }

    @Test
    fun returnsUnavailableForOversizedOrEncodedBodiesAndAfterClose() {
        withServer { server ->
            server.createContext("/oversized") { exchange ->
                exchange.responseHeaders.add("Content-Type", "text/html")
                exchange.sendResponseHeaders(200, 256L * 1024 + 1)
                exchange.close()
            }
            server.createContext("/encoded") { exchange ->
                val body = "compressed".toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/html")
                exchange.responseHeaders.add("Content-Encoding", "gzip")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
            val fetcher = fixtureFetcher()
            assertNull(runBlocking { fetcher.fetch(base(server) + "/oversized") })
            assertNull(runBlocking { fetcher.fetch(base(server) + "/encoded") })
            fetcher.close()
            assertNull(runBlocking { fetcher.fetch(base(server) + "/encoded") })
        }
    }

    @Test
    fun returnsSanitizedDirectImages() {
        withServer { server ->
            val source = BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB)
            val png = ByteArrayOutputStream().also { ImageIO.write(source, "png", it) }.toByteArray()
            server.createContext("/image") { exchange ->
                exchange.responseHeaders.add("Content-Type", "image/png")
                exchange.sendResponseHeaders(200, png.size.toLong())
                exchange.responseBody.use { it.write(png) }
            }
            server.start()
            val fetcher = fixtureFetcher()

            val direct = assertNotNull(runBlocking { fetcher.fetch(base(server) + "/image") })
            fetcher.close()

            assertEquals(PreviewKind.IMAGE, direct.kind)
            assertEquals(20, direct.image?.width)
        }
    }

    @Test
    fun preservesPageWhenOptionalThumbnailIsBlockedOrUnavailable() {
        withServer { server ->
            server.createContext("/blocked-thumbnail") { exchange ->
                val body = (
                    "<title>Still useful</title>" +
                        "<meta property=og:image content='https://127.0.0.1/private.png'>"
                    ).toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/html")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.createContext("/failed-thumbnail") { exchange ->
                val body = (
                    "<title>Also useful</title>" +
                        "<meta property=og:image content='https://thumb.invalid/image.png'>"
                    ).toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/html")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.createContext("/runtime-thumbnail") { exchange ->
                val body = (
                    "<title>Runtime still useful</title>" +
                        "<meta property=og:image content='https://runtime.invalid/image.png'>"
                    ).toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/html")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
            val localBase = base(server)
            val fetcher = PublicLinkPreviewFetcher(targetResolver = { raw ->
                when {
                    raw.startsWith(localBase) -> ValidatedPreviewTarget(raw.toHttpUrl(), listOf(InetAddress.getLoopbackAddress()))
                    raw.contains("thumb.invalid") -> throw java.io.IOException("synthetic thumbnail failure")
                    raw.contains("runtime.invalid") -> error("synthetic thumbnail failure")
                    else -> resolvePreviewTarget(raw) { listOf(InetAddress.getLoopbackAddress()) }
                }
            })

            val blocked = assertNotNull(runBlocking { fetcher.fetch(localBase + "/blocked-thumbnail") })
            val failed = assertNotNull(runBlocking { fetcher.fetch(localBase + "/failed-thumbnail") })
            val runtime = assertNotNull(runBlocking { fetcher.fetch(localBase + "/runtime-thumbnail") })
            fetcher.close()

            assertEquals("Still useful", blocked.title)
            assertNull(blocked.image)
            assertEquals(true, blocked.blockedThumbnail)
            assertEquals("Also useful", failed.title)
            assertNull(failed.image)
            assertEquals(false, failed.blockedThumbnail)
            assertEquals("Runtime still useful", runtime.title)
            assertNull(runtime.image)
            assertEquals(false, runtime.blockedThumbnail)
        }
    }

    @Test
    fun boundsChunkedBodiesAndWorkerCapacityAndDeadline() {
        withServer { server ->
            server.createContext("/chunked") { exchange ->
                exchange.responseHeaders.add("Content-Type", "text/html")
                exchange.sendResponseHeaders(200, 0)
                try {
                    exchange.responseBody.use { it.write(ByteArray(256 * 1024 + 1)) }
                } catch (_: java.io.IOException) {
                    exchange.close()
                }
            }
            server.start()
            val fetcher = fixtureFetcher()
            assertNull(runBlocking { fetcher.fetch(base(server) + "/chunked") })
            fetcher.close()
        }

        val rejectingExecutor = Executors.newSingleThreadExecutor().also(ExecutorService::shutdownNow)
        val rejected = PublicLinkPreviewFetcher(
            targetResolver = { throw AssertionError("rejected work must not resolve") },
            suppliedExecutor = rejectingExecutor,
        )
        assertNull(runBlocking { rejected.fetch("https://example.net/") })
        rejected.close()

        val timedOut = PublicLinkPreviewFetcher(
            targetResolver = {
                Thread.sleep(5_000)
                throw AssertionError("interrupted resolver must not complete")
            },
            timeout = Duration.ofMillis(20),
        )
        assertNull(runBlocking { timedOut.fetch("https://example.net/") })
        timedOut.close()

        val failed = PublicLinkPreviewFetcher(targetResolver = { error("synthetic") })
        assertNull(runBlocking { failed.fetch("https://example.net/") })
        failed.close()
    }

    @Test
    fun rejectsUnsupportedResponsesAndUsesACharsetFallback() {
        withServer { server ->
            server.createContext("/missing-location") { exchange ->
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            server.createContext("/not-found") { exchange ->
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
            }
            server.createContext("/missing-type") { exchange ->
                exchange.sendResponseHeaders(200, 0)
                exchange.close()
            }
            server.createContext("/unsupported") { exchange ->
                val body = "plain".toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/plain")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.createContext("/charset") { exchange ->
                val body = "<title>Fallback</title>".toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/html; charset=no-such-charset")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
            val fetcher = fixtureFetcher()

            assertNull(runBlocking { fetcher.fetch(base(server) + "/missing-location") })
            assertNull(runBlocking { fetcher.fetch(base(server) + "/not-found") })
            assertNull(runBlocking { fetcher.fetch(base(server) + "/missing-type") })
            assertNull(runBlocking { fetcher.fetch(base(server) + "/unsupported") })
            assertEquals("Fallback", runBlocking { fetcher.fetch(base(server) + "/charset") }?.title)
            fetcher.close()
            fetcher.close()
        }
    }

    @Test
    fun directSocketFactoryRefusesEveryPreconnectedSocketOverload() {
        DirectPreviewSocketFactory.createSocket().close()
        val address = InetAddress.getLoopbackAddress()

        assertFailsWith<SocketException> { DirectPreviewSocketFactory.createSocket("localhost", 443) }
        assertFailsWith<SocketException> { DirectPreviewSocketFactory.createSocket("localhost", 443, address, 0) }
        assertFailsWith<SocketException> { DirectPreviewSocketFactory.createSocket(address, 443) }
        assertFailsWith<SocketException> { DirectPreviewSocketFactory.createSocket(address, 443, address, 0) }
    }

    private fun fixtureFetcher(resolved: () -> Unit = {}) =
        PublicLinkPreviewFetcher(targetResolver = { raw ->
            resolved()
            ValidatedPreviewTarget(raw.toHttpUrl(), listOf(InetAddress.getLoopbackAddress()))
        })

    private fun base(server: HttpServer) = "http://127.0.0.1:" + server.address.port

    private fun withServer(block: (HttpServer) -> Unit) {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.executor = Executors.newCachedThreadPool()
        try {
            block(server)
        } finally {
            server.stop(0)
            (server.executor as ExecutorService).shutdownNow()
        }
    }
}
