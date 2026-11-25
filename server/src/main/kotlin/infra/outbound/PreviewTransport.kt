package ch.nokillswit.infra.outbound

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketException
import java.net.UnknownHostException
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val PREVIEW_WORKERS = 2
private const val PREVIEW_QUEUE_CAPACITY = 8
private const val MAX_REDIRECTS = 3
private val DEFAULT_TIMEOUT = Duration.ofSeconds(10)

internal class PublicLinkPreviewFetcher internal constructor(
    private val targetResolver: ((String) -> ValidatedPreviewTarget)? = null,
    private val timeout: Duration = DEFAULT_TIMEOUT,
    suppliedExecutor: ExecutorService? = null,
    private val customizeClient: (OkHttpClient.Builder) -> Unit = {},
    suppliedDnsResolver: BoundedPreviewDnsResolver? = null,
) : PreviewFetcher, AutoCloseable {
    private val executor = suppliedExecutor ?: previewExecutor()
    private val ownsExecutor = suppliedExecutor == null
    private val dnsResolver = if (targetResolver == null) suppliedDnsResolver ?: BoundedPreviewDnsResolver.production() else null
    private val operations = java.util.concurrent.ConcurrentHashMap.newKeySet<PreviewOperation>()
    private val closed = AtomicBoolean(false)

    override suspend fun fetch(url: String): PreviewContent? {
        if (closed.get()) return null
        val operation = PreviewOperation(url, targetResolver, dnsResolver, timeout, customizeClient)
        operations += operation
        return try {
            withTimeoutOrNull(timeout.toMillis()) {
                executor.awaitBounded(operation::cancel, operation::execute)
            }
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: BlockedPreviewUrlException) {
            throw cause
        } catch (_: RejectedExecutionException) {
            null
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        } finally {
            operation.cancel()
            operations -= operation
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        operations.forEach(PreviewOperation::cancel)
        if (ownsExecutor) executor.shutdownNow()
        dnsResolver?.close()
    }
}

private class PreviewOperation(
    private val initialUrl: String,
    private val targetResolver: ((String) -> ValidatedPreviewTarget)?,
    private val dnsResolver: BoundedPreviewDnsResolver?,
    private val timeout: Duration,
    private val customizeClient: (OkHttpClient.Builder) -> Unit,
) {
    private val cancelled = AtomicBoolean(false)
    private val currentCall = AtomicReference<Call?>()
    private val deadline = System.nanoTime() + timeout.toNanos()

    fun cancel() {
        cancelled.set(true)
        currentCall.get()?.cancel()
    }

    fun execute(): PreviewContent? {
        val primary = fetchResource(initialUrl) ?: return null
        if (primary.mediaType in SUPPORTED_IMAGES) {
            val image = reencodePreviewImage(primary.bytes, primary.mediaType) ?: return null
            return PreviewContent(PreviewKind.IMAGE, primary.finalUrl.toString(), null, image)
        }
        if (primary.mediaType !in HTML_MEDIA_TYPES) return null
        val page = parsePreviewPage(primary.bytes, primary.finalUrl.toString(), primary.charset)
        val optionalImage = fetchOptionalImage(page.imageUrl)
        return PreviewContent(
            PreviewKind.PAGE,
            primary.finalUrl.toString(),
            page.title,
            optionalImage.image,
            optionalImage.blocked,
        )
    }

    private fun fetchOptionalImage(url: String?): OptionalPreviewImage {
        if (url == null) return OptionalPreviewImage()
        return try {
            val resource = fetchResource(url)?.takeIf { it.mediaType in SUPPORTED_IMAGES }
            val image = resource?.let { reencodePreviewImage(it.bytes, it.mediaType) }
            failIfCancelled()
            OptionalPreviewImage(image)
        } catch (_: BlockedPreviewUrlException) {
            failIfCancelled()
            OptionalPreviewImage(blocked = true)
        } catch (_: IOException) {
            failIfCancelled()
            OptionalPreviewImage()
        } catch (_: RuntimeException) {
            failIfCancelled()
            OptionalPreviewImage()
        }
    }

    private fun fetchResource(raw: String): PreviewResource? {
        var next = raw
        var redirects = 0
        while (true) {
            failIfCancelled()
            val target = targetResolver?.invoke(next) ?: resolvePreviewTarget(next) { host ->
                checkNotNull(dnsResolver).resolve(host, deadline)
            }
            val result = exchange(target) ?: return null
            if (result.redirect == null) return result.resource
            redirects += 1
            if (redirects > MAX_REDIRECTS) return null
            next = target.url.resolve(result.redirect)?.toString() ?: return null
        }
    }

    private fun exchange(target: ValidatedPreviewTarget): PreviewExchange? {
        val pool = ConnectionPool(0, 1, TimeUnit.NANOSECONDS)
        val client = buildClient(target, pool)
        val request = Request.Builder().url(target.url)
            .header("Accept", "text/html, application/xhtml+xml, image/png, image/jpeg")
            .header("Accept-Encoding", "identity")
            .get().build()
        val call = client.newCall(request)
        if (!currentCall.compareAndSet(null, call) || cancelled.get()) {
            call.cancel()
            throw InterruptedIOException("preview cancelled")
        }
        try {
            val response = call.execute()
            try {
                return consume(response, target.url, call).also { if (it == null) call.cancel() }
            } catch (cause: Throwable) {
                call.cancel()
                throw cause
            } finally {
                response.close()
            }
        } finally {
            currentCall.compareAndSet(call, null)
            call.cancel()
            pool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }

    private fun consume(response: Response, finalUrl: HttpUrl, call: Call): PreviewExchange? {
        if (response.code in REDIRECT_CODES) {
            val location = response.header("Location") ?: return null
            call.cancel()
            return PreviewExchange(redirect = location)
        }
        if (response.code != 200 || response.header("Content-Encoding")?.lowercase() !in setOf(null, "identity")) {
            call.cancel()
            return null
        }
        val contentType = response.body.contentType()
        val mediaType = contentType?.let { it.type.lowercase() + "/" + it.subtype.lowercase() } ?: return null
        val limit = when (mediaType) {
            in HTML_MEDIA_TYPES -> MAX_PREVIEW_HTML_BYTES
            in SUPPORTED_IMAGES -> MAX_PREVIEW_IMAGE_BYTES
            else -> return null
        }
        val bytes = readCapped(response, limit)
        val charset = try {
            contentType.charset(Charsets.UTF_8)?.name() ?: Charsets.UTF_8.name()
        } catch (_: IllegalArgumentException) {
            Charsets.UTF_8.name()
        }
        return PreviewExchange(resource = PreviewResource(finalUrl, mediaType, charset, bytes))
    }

    private fun buildClient(target: ValidatedPreviewTarget, pool: ConnectionPool): OkHttpClient {
        val pinnedDns = Dns { hostname ->
            if (hostname != target.url.host) throw UnknownHostException("unexpected preview hostname")
            target.addresses
        }
        val remaining = maxOf(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))
        return OkHttpClient.Builder()
            .dns(pinnedDns)
            .proxy(Proxy.NO_PROXY)
            .socketFactory(DirectPreviewSocketFactory)
            .connectionPool(pool)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .fastFallback(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .callTimeout(remaining, TimeUnit.MILLISECONDS)
            .connectTimeout(remaining, TimeUnit.MILLISECONDS)
            .readTimeout(remaining, TimeUnit.MILLISECONDS)
            .writeTimeout(remaining, TimeUnit.MILLISECONDS)
            .apply(customizeClient)
            .build()
    }

    private fun failIfCancelled() {
        if (cancelled.get() || Thread.currentThread().isInterrupted || System.nanoTime() >= deadline) {
            throw InterruptedIOException("preview cancelled")
        }
    }
}

private fun readCapped(response: Response, limit: Int): ByteArray {
    val declared = response.body.contentLength()
    if (declared > limit) throw PreviewTooLargeException()
    val output = ByteArrayOutputStream(minOf(limit, declared.coerceAtLeast(0).toInt()))
    val buffer = ByteArray(8_192)
    val source = response.body.source()
    var received = 0
    while (true) {
        val count = source.read(buffer, 0, minOf(buffer.size, limit - received + 1))
        if (count == -1) break
        received += count
        if (received > limit) throw PreviewTooLargeException()
        output.write(buffer, 0, count)
    }
    if (declared >= 0 && received.toLong() != declared) throw IOException("truncated preview response")
    return output.toByteArray()
}

private suspend fun <T> ExecutorService.awaitBounded(onCancel: () -> Unit, block: () -> T): T =
    suspendCancellableCoroutine { continuation ->
        val task = object : FutureTask<T>(block) {
            override fun done() {
                if (!continuation.isActive) return
                try {
                    continuation.resume(get())
                } catch (_: java.util.concurrent.CancellationException) {
                    return
                } catch (cause: ExecutionException) {
                    continuation.resumeWithException(cause.cause ?: cause)
                } catch (cause: InterruptedException) {
                    Thread.currentThread().interrupt()
                    continuation.resumeWithException(cause)
                }
            }
        }
        continuation.invokeOnCancellation {
            onCancel()
            task.cancel(true)
            (this as? ThreadPoolExecutor)?.remove(task)
        }
        try {
            execute(task)
        } catch (cause: RejectedExecutionException) {
            task.cancel(true)
            if (continuation.isActive) continuation.resumeWithException(cause)
        }
    }

private fun previewExecutor(): ExecutorService = ThreadPoolExecutor(
    PREVIEW_WORKERS,
    PREVIEW_WORKERS,
    0,
    TimeUnit.MILLISECONDS,
    ArrayBlockingQueue(PREVIEW_QUEUE_CAPACITY),
    ThreadFactory { runnable -> Thread(runnable, "link-preview").apply { isDaemon = true } },
    ThreadPoolExecutor.AbortPolicy(),
)

private data class PreviewResource(val finalUrl: HttpUrl, val mediaType: String, val charset: String, val bytes: ByteArray)
private data class PreviewExchange(val resource: PreviewResource? = null, val redirect: String? = null)
private data class OptionalPreviewImage(val image: PreviewImage? = null, val blocked: Boolean = false)
private class PreviewTooLargeException : IOException()
private val HTML_MEDIA_TYPES = setOf("text/html", "application/xhtml+xml")
private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

internal object DirectPreviewSocketFactory : SocketFactory() {
    override fun createSocket(): Socket = Socket(Proxy.NO_PROXY)
    override fun createSocket(host: String, port: Int): Socket = unsupported()
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = unsupported()
    override fun createSocket(host: InetAddress, port: Int): Socket = unsupported()
    override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket = unsupported()
    private fun unsupported(): Socket = throw SocketException("connected socket creation is disabled")
}
