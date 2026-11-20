package ch.nokillswit.threads

import ch.nokillswit.infra.catchingFailures
import ch.nokillswit.infra.db.R2dbcDatabaseKey
import ch.nokillswit.plugins.scrubbedSummary
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("ch.nokillswit.threads.MessageTagReindex")

private const val SHUTDOWN_JOIN_TIMEOUT_MS = 2_000L

/**
 * Published once the boot task has started: how many messages it indexed (0 when the reindex is switched off), or null when it
 * failed. A TEST seam (`MessageTagReindexTest` awaits it); nothing in the application reads it.
 */
val MessageTagReindexKey = AttributeKey<Deferred<Int?>>("MessageTagReindex")

/**
 * The boot task of the hashtag index (`chat-model.md` "Hashtags (V15)"): once the application has started, in its own scope, the tag rows
 * of DELETED messages are removed ([MessageTagService.purgeDeletedTags]: a 0.4.0 binary deletes a message without knowing the index, and
 * the tag rows of a deleted message must not outlive its scrubbed body), then so are its mention, notice, reaction and pin rows
 * ([StaleDerivedRows.purge]: the same net for a 0.5.x binary run against V17 and later), then, when `threads.tagReindexOnBoot` is on,
 * every live message below [TAGS_VERSION] is indexed ([MessageTagService.reindexStale]), so a message written before 0.5.0 (or before a
 * grammar bump) is found by its tags. The purge runs whatever the switch says: it only ever deletes rows that must not exist, in
 * batches, and the switch exists to skip the one expensive walk (the test harness turns the reindex off). Until the reindex ends,
 * the tag filter misses the older messages: seconds at today's sizes, and the counts are logged. A rollback below 0.5.0 that EDITED
 * messages needs `UPDATE messages SET tags_version = 0` before the next 0.5.0 boot (`persistence.md` "Hashtags (V15)"). The three steps
 * fail alone ([bootRun]); each logs counts and, on failure, CLASS NAMES only (the scrubber: a database message can echo a bound body or
 * tag), and its rows wait for the next boot.
 * Cancelled before the pool is disposed.
 */
fun Application.configureMessageTagReindex() {
    val key = "threads.tagReindexOnBoot"
    val raw = environment.config.property(key).getString()
    val reindex = raw.toBooleanStrictOrNull() ?: error("Config \"$key\" must be true or false (was \"$raw\")")
    monitor.subscribe(ApplicationStarted) { app ->
        val tags = app.attributes[MessageTagServiceKey]
        val stale = StaleDerivedRows(app.attributes[R2dbcDatabaseKey])
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        app.attributes.put(
            MessageTagReindexKey,
            scope.async { bootRun(tags::purgeDeletedTags, stale::purge) { if (reindex) tags.reindexStale() else 0 } },
        )
        app.monitor.subscribe(ApplicationStopping) {
            val rootJob = scope.coroutineContext[Job]
            scope.cancel()
            if (rootJob != null) runBlocking { withTimeoutOrNull(SHUTDOWN_JOIN_TIMEOUT_MS) { rootJob.join() } }
        }
    }
}

/**
 * The boot task: its three steps ([purgeTags], [purgeDerived], [reindex]) each have their OWN failure handling and log line, so a failure
 * in one leaves that step's rows for the next boot and never skips the others; ANY failure is logged (scrubbed), cancellation is
 * rethrown ([catchingFailures]). The answer is the reindex's count, null when the reindex itself failed. Lambdas, so a test can fail one.
 */
internal suspend fun bootRun(
    purgeTags: suspend () -> Int,
    purgeDerived: suspend () -> Int,
    reindex: suspend () -> Int,
): Int? {
    val tagRows = catchingFailures(
        block = { purgeTags() },
        onFailure = { e ->
            log.warn("purge of the tag rows of deleted messages failed, the rows stay until the next boot: {}", e.scrubbedSummary())
            0
        },
    )
    val derivedRows = catchingFailures(
        block = { purgeDerived() },
        onFailure = { e ->
            log.warn(
                "purge of the mention, notice, reaction and pin rows of deleted messages failed, the rows stay until the next boot: {}",
                e.scrubbedSummary(),
            )
            0
        },
    )
    return catchingFailures(
        block = {
            reindex().also {
                if (it > 0 || tagRows > 0 || derivedRows > 0) {
                    log.info(
                        "message boot pass: {} message(s) indexed, {} tag row(s) and {} mention, notice, reaction and pin row(s) " +
                            "of deleted messages removed",
                        it, tagRows, derivedRows,
                    )
                }
            }
        },
        onFailure = { e ->
            log.warn("message tag reindex failed, the rows stay stale until the next boot: {}", e.scrubbedSummary())
            null
        },
    )
}

