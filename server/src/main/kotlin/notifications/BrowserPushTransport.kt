package ch.nokillswit.notifications

import ch.nokillswit.infra.outbound.BoundedPreviewDnsResolver
import ch.nokillswit.infra.outbound.ValidatedPreviewTarget
import ch.nokillswit.infra.outbound.resolvePreviewTarget
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.io.IOException
import java.net.Proxy
import java.net.UnknownHostException
import java.time.Duration
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellationException
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal enum class PushResult { DELIVERED, EXPIRED, FAILED }

internal fun interface PushTransport {
    suspend fun send(endpoint: String): PushResult
}

internal class WebPushTransport(
    private val config: BrowserPushConfig,
    private val timeout: Duration = Duration.ofSeconds(5),
    private val dnsResolver: BoundedPreviewDnsResolver = BoundedPreviewDnsResolver.production(),
    private val targetResolver: ((String, Long) -> ValidatedPreviewTarget)? = null,
    private val requestSender: (suspend (ValidatedPreviewTarget, Request) -> Int)? = null,
) : PushTransport, AutoCloseable {
    override suspend fun send(endpoint: String): PushResult = try {
        sendValidated(endpoint)
    } catch (cause: CancellationException) {
        throw cause
    } catch (_: IOException) {
        PushResult.FAILED
    } catch (_: RuntimeException) {
        PushResult.FAILED
    }

    private suspend fun sendValidated(endpoint: String): PushResult {
        val canonical = validatePushEndpoint(endpoint)
        val deadline = System.nanoTime() + timeout.toNanos()
        val target = targetResolver?.invoke(canonical, deadline) ?: resolvePreviewTarget(canonical) { host ->
            dnsResolver.resolve(host, deadline)
        }
        checkProviderHost(target.url)
        val request = Request.Builder()
            .url(target.url)
            .header("Authorization", vapidAuthorization(target.url))
            .header("TTL", "60")
            .header("Urgency", "normal")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        if (requestSender != null) return requestSender.invoke(target, request).toPushResult()
        val client = clientFor(target)
        return try {
            client.newCall(request).await().use { response ->
                response.code.toPushResult()
            }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }

    private fun vapidAuthorization(endpoint: HttpUrl): String {
        val origin = "https://${endpoint.host}"
        val token = JWT.create()
            .withAudience(origin)
            .withSubject(config.subject)
            .withExpiresAt(Date(System.currentTimeMillis() + VAPID_TTL_MILLIS))
            .sign(Algorithm.ECDSA256(config.publicKeyObject, config.privateKeyObject))
        return "vapid t=$token, k=${config.publicKey}"
    }

    private fun clientFor(target: ValidatedPreviewTarget): OkHttpClient {
        val pinnedDns = Dns { hostname ->
            if (hostname != target.url.host) throw UnknownHostException("unexpected push hostname")
            target.addresses
        }
        return OkHttpClient.Builder()
            .dns(pinnedDns)
            .proxy(Proxy.NO_PROXY)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .fastFallback(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .callTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
            .connectTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
            .readTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
            .writeTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
            .build()
    }

    override fun close() = dnsResolver.close()

    private companion object {
        const val VAPID_TTL_MILLIS = 12 * 60 * 60 * 1000L
    }
}

private fun Int.toPushResult(): PushResult = when (this) {
    200, 201, 202 -> PushResult.DELIVERED
    404, 410 -> PushResult.EXPIRED
    else -> PushResult.FAILED
}

private fun checkProviderHost(url: HttpUrl) {
    // validatePushEndpoint owns the allowlist; this second check ensures an injected target cannot change the DNS name.
    validatePushEndpoint(url.toString())
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) else response.close()
        }
    })
}
