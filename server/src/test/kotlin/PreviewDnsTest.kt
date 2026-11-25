package ch.nokillswit

import ch.nokillswit.infra.outbound.BlockedPreviewUrlException
import ch.nokillswit.infra.outbound.BoundedPreviewDnsResolver
import ch.nokillswit.infra.outbound.PreviewDnsSettings
import ch.nokillswit.infra.outbound.PreviewDnsUnavailableException
import ch.nokillswit.infra.outbound.PublicLinkPreviewFetcher
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class PreviewDnsTest {
    @Test
    fun `uses an absolute name and returns every IPv4 answer within the lookup budget`() {
        val requested = AtomicReference<String>()
        val answers = listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("1.1.1.1"))
        val resolver = BoundedPreviewDnsResolver(startLookup = {
            requested.set(it)
            CompletableFuture.completedFuture(answers)
        })

        val result = resolver.resolve("cdn.example.net", futureDeadline())
        resolver.close()

        assertEquals("cdn.example.net.", requested.get())
        assertEquals(answers, result)
    }

    @Test
    fun `cancels a timed out query and permits the bounded fetch worker to recover`() {
        val queries = AtomicInteger()
        val stalled = CompletableFuture<List<InetAddress>>()
        val resolver = BoundedPreviewDnsResolver(
            startLookup = {
                if (queries.incrementAndGet() == 1) stalled else CompletableFuture.completedFuture(
                    listOf(InetAddress.getLoopbackAddress()),
                )
            },
            settings = PreviewDnsSettings(lookupTimeout = Duration.ofMillis(20)),
        )
        val fetcher = PublicLinkPreviewFetcher(
            timeout = Duration.ofMillis(500),
            suppliedDnsResolver = resolver,
        )

        assertNull(runBlocking { fetcher.fetch("https://public.example.net/page") })
        assertTrue(stalled.isCancelled)
        assertFailsWith<BlockedPreviewUrlException> {
            runBlocking { fetcher.fetch("https://public.example.net/page") }
        }
        fetcher.close()
        assertEquals(2, queries.get())
    }

    @Test
    fun `interrupt cancels the outstanding query and close releases resources once`() {
        val started = CountDownLatch(1)
        val stalled = CompletableFuture<List<InetAddress>>()
        val closes = AtomicInteger()
        val failure = AtomicReference<Throwable>()
        val interrupted = AtomicBoolean()
        val resolver = BoundedPreviewDnsResolver(
            startLookup = {
                started.countDown()
                stalled
            },
            closeResources = { closes.incrementAndGet() },
        )
        val thread = Thread {
            try {
                resolver.resolve("cdn.example.net", futureDeadline())
            } catch (cause: Throwable) {
                failure.set(cause)
                interrupted.set(Thread.currentThread().isInterrupted)
            }
        }

        thread.start()
        assertTrue(started.await(1, TimeUnit.SECONDS))
        thread.interrupt()
        thread.join(1_000)
        resolver.close()
        resolver.close()

        assertTrue(stalled.isCancelled)
        assertTrue(failure.get() is InterruptedIOException)
        assertTrue(interrupted.get())
        assertEquals(1, closes.get())
        assertFailsWith<PreviewDnsUnavailableException> {
            resolver.resolve("cdn.example.net", futureDeadline())
        }
    }

    @Test
    fun `production DNS limits are explicit and shorter than the preview deadline`() {
        val settings = PreviewDnsSettings()

        assertEquals(Duration.ofSeconds(1), settings.queryTimeout)
        assertEquals(Duration.ofSeconds(2), settings.lookupTimeout)
        assertEquals(4, settings.maxQueriesPerResolve)
        assertTrue(settings.lookupTimeout < Duration.ofSeconds(10))
    }

    @Test
    fun `rejects an expired budget and distinguishes name failure from resolver failure`() {
        val expired = BoundedPreviewDnsResolver(startLookup = {
            throw AssertionError("expired lookup must not start")
        })
        assertFailsWith<InterruptedIOException> {
            expired.resolve("cdn.example.net", System.nanoTime() - 1)
        }
        expired.close()

        val unknown = BoundedPreviewDnsResolver(startLookup = {
            CompletableFuture.failedFuture(UnknownHostException("synthetic"))
        })
        assertFailsWith<UnknownHostException> {
            unknown.resolve("missing.example.net", futureDeadline())
        }
        unknown.close()

        val failed = BoundedPreviewDnsResolver(startLookup = {
            CompletableFuture.failedFuture(IllegalStateException("synthetic"))
        })
        assertFailsWith<PreviewDnsUnavailableException> {
            failed.resolve("cdn.example.net", futureDeadline())
        }
        failed.close()
    }

    @Test
    fun `production resolver starts and releases its lifecycle without a query`() {
        val resolver = BoundedPreviewDnsResolver.production()
        val fetcher = PublicLinkPreviewFetcher()

        resolver.close()
        resolver.close()
        fetcher.close()
        fetcher.close()
    }

    private fun futureDeadline(): Long = System.nanoTime() + Duration.ofSeconds(5).toNanos()
}
