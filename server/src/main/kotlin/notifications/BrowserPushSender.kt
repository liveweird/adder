package ch.nokillswit.notifications

import ch.nokillswit.infra.time.MILLIS_PER_DAY
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("ch.nokillswit.notifications.BrowserPushSender")

internal const val MAX_PUSH_PER_RUN = 500
internal const val PUSH_SUBSCRIPTION_RETENTION_MILLIS = 90L * MILLIS_PER_DAY
internal const val PUSH_QUEUE_MAX_AGE_MILLIS = 15 * 60 * 1000L

/** Claims before I/O, then sends outside every database transaction. A claimed device is attempted at most once per tick. */
internal class BrowserPushSender(
    private val service: BrowserPushService,
    private val transport: PushTransport,
    private val maxPerRun: Int,
    private val clock: () -> Long,
) {
    suspend fun run() {
        service.purgeStale(clock() - PUSH_SUBSCRIPTION_RETENTION_MILLIS)
        val claimed = service.claim(maxPerRun, clock() - PUSH_QUEUE_MAX_AGE_MILLIS)
        val permits = Semaphore(PUSH_SEND_CONCURRENCY)
        coroutineScope {
            claimed.map { push ->
                async {
                    permits.withPermit {
                        try {
                            val endpoint = service.eligibleEndpoint(push) ?: return@withPermit
                            if (transport.send(endpoint) == PushResult.EXPIRED) service.removeExpired(push.subscriptionId)
                        } catch (cause: CancellationException) {
                            throw cause
                        } catch (cause: RuntimeException) {
                            log.warn(
                                "browser push delivery isolated after unexpected failure: subscriptionId={}, errorType={}",
                                push.subscriptionId.toLong(),
                                cause.javaClass.simpleName,
                            )
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private companion object {
        const val PUSH_SEND_CONCURRENCY = 4
    }
}
