package ch.nokillswit.infra.db

import ch.nokillswit.infra.config.requireConfigInt
import ch.nokillswit.notifications.NotificationService
import ch.nokillswit.notifications.NotificationServiceKey
import ch.nokillswit.rooms.RoomMemberService
import ch.nokillswit.rooms.RoomService
import ch.nokillswit.rooms.RoomWorkflowService
import ch.nokillswit.rooms.WorkflowUsageKey
import ch.nokillswit.threads.MessageMentionService
import ch.nokillswit.threads.MessageReactionService
import ch.nokillswit.threads.MessageReactionServiceKey
import ch.nokillswit.threads.MessageService
import ch.nokillswit.threads.MessageServiceKey
import ch.nokillswit.threads.MessageTagService
import ch.nokillswit.threads.MessageTagServiceKey
import ch.nokillswit.threads.PinCleanup
import ch.nokillswit.threads.PinService
import ch.nokillswit.threads.PinServiceKey
import ch.nokillswit.threads.ReadLimiter
import ch.nokillswit.threads.ThreadEventService
import ch.nokillswit.threads.ThreadReadService
import ch.nokillswit.threads.ThreadReadServiceKey
import ch.nokillswit.threads.ThreadSearchService
import ch.nokillswit.threads.ThreadSearchServiceKey
import ch.nokillswit.threads.ThreadService
import ch.nokillswit.threads.ThreadServiceKey
import ch.nokillswit.threads.ThreadStatusService
import ch.nokillswit.threads.ThreadStatusServiceKey
import ch.nokillswit.threads.ThreadWorkflowUsage
import io.ktor.server.application.Application
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

/**
 * The chat services of the composition root (`rooms/`' neighbours: `threads/` and `notifications/`), split out of [configureDatabase] so
 * that file stays under the size cap: it builds the thread, message, read-marker, pin, search and notice services in their one possible
 * order and publishes each into [Application.attributes]. Like `Database.kt` it is a composition root (`PackageBoundaryTest`): the only
 * place besides it that may name a feature package.
 */
internal fun Application.publishChatServices(
    database: R2dbcDatabase,
    roomService: RoomService,
    roomMemberService: RoomMemberService,
    roomWorkflowService: RoomWorkflowService,
    readMaxConcurrent: Int,
    searchMaxConcurrent: Int,
) {
    // The thread aggregate (V14): a thread write runs under the room lock and reads the room through the rooms/ services only.
    val threadEventService = ThreadEventService(database)
    // The hashtag index (V15) is written inside the message writes, so the message service holds it; a boot reindex reads it back.
    // The filtered thread list and the tag list share one limiter: ONE `threads.readTimeoutSeconds` deadline per read and at most
    // `threads.readMaxConcurrent` reads at once in the process (both boot-validated).
    val readLimiter = ReadLimiter(
        timeoutSeconds = requireConfigInt(environment.config, "threads.readTimeoutSeconds", min = 1, max = 30),
        maxConcurrent = readMaxConcurrent,
    )
    val messageTagService = MessageTagService(database, readLimiter)
    attributes.put(MessageTagServiceKey, messageTagService)
    // The notices (V18) are made inside the chat's own writes through the `ThreadNotices` port `threads/` declares: this root hands the
    // implementation to the services that call it (`notifications/` imports `threads/`, never the reverse).
    val notificationService = NotificationService(database, roomService)
    attributes.put(NotificationServiceKey, notificationService)
    // The mention index (V17) is the message write's first follower: it is built here and handed to the service, which runs it. The other
    // followers (V21: a deleted message loses its pin) run after it, inside the same write.
    val messageMentionService = MessageMentionService(roomMemberService, notificationService)
    // The reactions (V20) are read by the message reads and clear a deleted message's rows as a follower, which the service always runs.
    val reactionService = MessageReactionService(database)
    attributes.put(MessageReactionServiceKey, reactionService)
    val messageService = MessageService(
        database, roomService, messageTagService, messageMentionService, listOf(PinCleanup()), reactionService,
    )
    attributes.put(MessageServiceKey, messageService)
    attributes.put(PinServiceKey, PinService(database, messageService))
    attributes.put(ThreadReadServiceKey, ThreadReadService(database, roomMemberService, notificationService))
    attributes.put(
        ThreadServiceKey,
        ThreadService(
            database,
            roomService,
            roomWorkflowService,
            roomMemberService,
            threadEventService,
            messageService,
            readLimiter,
        ),
    )
    // The full-text search (V16): one read transaction under ONE `search.timeoutSeconds` deadline, at most `search.maxConcurrent` at once
    // (both boot-validated).
    attributes.put(
        ThreadSearchServiceKey,
        ThreadSearchService(
            database,
            roomService,
            requireConfigInt(environment.config, "search.timeoutSeconds", min = 1, max = 30),
            searchMaxConcurrent,
        ),
    )
    attributes.put(
        ThreadStatusServiceKey,
        ThreadStatusService(database, roomService, roomWorkflowService, roomMemberService, threadEventService, notificationService),
    )
    // The workflow's thread-usage port (S6): rooms/ declares it and never imports threads/, so this root registers the implementation.
    attributes.put(WorkflowUsageKey, ThreadWorkflowUsage(roomWorkflowService, threadEventService))
}
