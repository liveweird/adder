package ch.nokillswit.infra.outbound

import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.resolver.ResolvedAddressTypes
import io.netty.resolver.dns.DnsNameResolver
import io.netty.resolver.dns.DnsNameResolverBuilder
import io.netty.resolver.dns.DnsServerAddressStreamProviders
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

internal data class PreviewDnsSettings(
    val queryTimeout: Duration = Duration.ofSeconds(1),
    val lookupTimeout: Duration = Duration.ofSeconds(2),
    val maxQueriesPerResolve: Int = 4,
)

internal class BoundedPreviewDnsResolver internal constructor(
    private val startLookup: (String) -> Future<List<InetAddress>>,
    private val closeResources: () -> Unit = {},
    private val settings: PreviewDnsSettings = PreviewDnsSettings(),
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    internal fun resolve(host: String, operationDeadlineNanos: Long): List<InetAddress> {
        if (closed.get()) throw PreviewDnsUnavailableException()
        val remaining = operationDeadlineNanos - System.nanoTime()
        val budget = minOf(remaining, settings.lookupTimeout.toNanos())
        if (budget <= 0) throw InterruptedIOException("preview deadline exceeded")
        val future = startLookup(absoluteDnsName(host))
        return try {
            future.get(budget, TimeUnit.NANOSECONDS)
        } catch (cause: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw InterruptedIOException("preview DNS interrupted").apply { initCause(cause) }
        } catch (cause: TimeoutException) {
            future.cancel(true)
            throw PreviewDnsUnavailableException(cause)
        } catch (cause: ExecutionException) {
            val failure = cause.cause ?: cause
            if (failure is UnknownHostException && !DnsNameResolver.isTransportOrTimeoutError(failure)) throw failure
            throw PreviewDnsUnavailableException(failure)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) closeResources()
    }

    internal companion object {
        fun production(settings: PreviewDnsSettings = PreviewDnsSettings()): BoundedPreviewDnsResolver {
            val threadFactory = ThreadFactory { runnable ->
                Thread(runnable, "link-preview-dns").apply { isDaemon = true }
            }
            val eventLoop = MultiThreadIoEventLoopGroup(1, threadFactory, NioIoHandler.newFactory())
            val resolver = DnsNameResolverBuilder(eventLoop.next())
                .datagramChannelType(NioDatagramChannel::class.java)
                .nameServerProvider(DnsServerAddressStreamProviders.platformDefault())
                .resolvedAddressTypes(ResolvedAddressTypes.IPV4_ONLY)
                .completeOncePreferredResolved(false)
                .queryTimeoutMillis(settings.queryTimeout.toMillis())
                .maxQueriesPerResolve(settings.maxQueriesPerResolve)
                .searchDomains(emptyList())
                .ndots(1)
                .build()
            return BoundedPreviewDnsResolver(
                startLookup = resolver::resolveAll,
                closeResources = {
                    resolver.close()
                    eventLoop.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly()
                },
                settings = settings,
            )
        }
    }
}

internal class PreviewDnsUnavailableException(cause: Throwable? = null) : IOException("Preview DNS unavailable", cause)
