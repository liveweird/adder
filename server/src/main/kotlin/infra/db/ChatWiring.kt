package ch.nokillswit.infra.db

import ch.nokillswit.home.HomeService
import ch.nokillswit.home.HomeServiceKey
import ch.nokillswit.infra.config.requireConfigInt
import ch.nokillswit.infra.mail.mailer
import ch.nokillswit.infra.outbound.PreviewFetcher
import ch.nokillswit.infra.outbound.PublicLinkPreviewFetcher
import ch.nokillswit.infra.presence.ForegroundPresence
import ch.nokillswit.infra.presence.ForegroundPresenceKey
import ch.nokillswit.notifications.MAX_BROADCASTS_PER_ACTOR
import ch.nokillswit.notifications.MAX_EMAIL_BUDGET_PER_ACTOR
import ch.nokillswit.notifications.NoticeWriter
import ch.nokillswit.notifications.NotificationEmailBudget
import ch.nokillswit.notifications.NotificationService
import ch.nokillswit.notifications.NotificationServiceKey
import ch.nokillswit.threads.ThreadMuteService
import ch.nokillswit.threads.ThreadMuteServiceKey
import ch.nokillswit.rooms.RoomEnrollmentService
import ch.nokillswit.rooms.RoomEnrollmentServiceKey
import ch.nokillswit.rooms.RoomMemberService
import ch.nokillswit.rooms.RoomMemberServiceKey
import ch.nokillswit.rooms.RoomService
import ch.nokillswit.rooms.RoomWorkflowService
import ch.nokillswit.rooms.WorkflowUsageKey
import ch.nokillswit.teams.TeamService
import ch.nokillswit.threads.MessageMentionService
import ch.nokillswit.threads.MessageLinkPreviewService
import ch.nokillswit.threads.MessageLinkPreviewServiceKey
import ch.nokillswit.threads.MessageReactionService
import ch.nokillswit.threads.MessageReactionServiceKey
import ch.nokillswit.threads.MessageService
import ch.nokillswit.threads.MessageServiceKey
import ch.nokillswit.threads.MessageTagService
import ch.nokillswit.threads.MessageTagServiceKey
import ch.nokillswit.threads.PinCleanup
import ch.nokillswit.threads.PinService
import ch.nokillswit.threads.PinServiceKey
import ch.nokillswit.threads.NOTIFICATION_READS_PER_USER
import ch.nokillswit.threads.ReadLimiter
import ch.nokillswit.threads.THREAD_READS_PER_USER
import ch.nokillswit.threads.ThreadEventService
import ch.nokillswit.threads.ThreadReadLimiters
import ch.nokillswit.threads.ThreadReadService
import ch.nokillswit.threads.ThreadReadServiceKey
import ch.nokillswit.threads.ThreadSearchService
import ch.nokillswit.threads.ThreadSearchServiceKey
import ch.nokillswit.threads.ThreadService
import ch.nokillswit.threads.ThreadServiceKey
import ch.nokillswit.threads.ThreadStatusService
import ch.nokillswit.threads.ThreadStatusServiceKey
import ch.nokillswit.threads.ThreadTagService
import ch.nokillswit.threads.ThreadTagServiceKey
import ch.nokillswit.threads.ThreadWatchService
import ch.nokillswit.threads.ThreadWatchServiceKey
import ch.nokillswit.threads.ThreadWorkflowUsage
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.util.AttributeKey
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

internal val PreviewFetcherKey = AttributeKey<PreviewFetcher>("PreviewFetcher")

/**
 * The chat services of the composition root (`rooms/`' neighbours: `threads/` and `notifications/`), split out of [configureDatabase] so
 * that file stays under the size cap: it builds the thread, message, read-marker, pin, search and notice services in their one possible
 * order and publishes each into [Application.attributes]. Like `Database.kt` it is a composition root (`PackageBoundaryTest`): the only
 * place besides it that may name a feature package.
 */
internal fun Application.publishChatServices(
    database: R2dbcDatabase,
    roomService: RoomService,
    roomWorkflowService: RoomWorkflowService,
    teamService: TeamService,
    readMaxConcurrent: Int,
    searchMaxConcurrent: Int,
) {
    val previewFetcher = PublicLinkPreviewFetcher()
    attributes.put(PreviewFetcherKey, previewFetcher)
    monitor.subscribe(ApplicationStopped) { previewFetcher.close() }
    // The thread aggregate (V14): a thread write runs under the room lock and reads the room through the rooms/ services only.
    val threadEventService = ThreadEventService(database)
    // The hashtag index (V15) is written inside the message writes, so the message service holds it; a boot reindex reads it back.
    // The filtered thread list, the tag list and Home share one limiter (the thread detail and notice list have their own, below): ONE
    // `threads.readTimeoutSeconds` deadline per read and at most
    // `threads.readMaxConcurrent` reads at once in the process (both boot-validated).
    val readLimiter = ReadLimiter(
        timeoutSeconds = requireConfigInt(environment.config, "threads.readTimeoutSeconds", min = 1, max = 30),
        maxConcurrent = readMaxConcurrent,
    )
    // The caller's own notice list has a limiter of its own (the same deadline, a small process cap and per-user cap), so a flood of its
    // re-reads cannot take the thread lists' share of the pool nor the reverse; `requireReadersUnderPool` counts it.
    val notificationReadLimiter = ReadLimiter(
        timeoutSeconds = requireConfigInt(environment.config, "threads.readTimeoutSeconds", min = 1, max = 30),
        maxConcurrent = NOTIFICATION_READ_MAX_CONCURRENT,
        perUser = NOTIFICATION_READS_PER_USER,
    )
    // The thread detail has a limiter of its own too (the same deadline): it is NOT in the `chat-read` bucket, so an unbucketed loop of it
    // must not be able to take the lists' permits (`requireReadersUnderPool` counts its cap).
    val threadReadLimiter = ReadLimiter(
        timeoutSeconds = requireConfigInt(environment.config, "threads.readTimeoutSeconds", min = 1, max = 30),
        maxConcurrent = THREAD_READ_MAX_CONCURRENT,
        perUser = THREAD_READS_PER_USER,
    )
    val messageTagService = MessageTagService(database, readLimiter)
    attributes.put(MessageTagServiceKey, messageTagService)
    // The notices (V18) are made inside the chat's own writes through the `ThreadNotices` port `threads/` declares: this root hands the
    // implementation (`NoticeWriter`) to the services that call it (`notifications/` imports `threads/`, never the reverse).
    // The per-actor digest budget (V24, boot-validated): the most distinct people one actor's mentions and hand-overs can make eligible for
    // the email digest in a UTC day; the bell is untouched.
    val emailBudget = NotificationEmailBudget(
        perDay = requireConfigInt(
            environment.config, "notifications.email.maxRecipientsPerActorPerDay", min = 1, max = MAX_EMAIL_BUDGET_PER_ACTOR,
        ),
        clock = ::nowMillis,
        // The wide-event budget (V29, boot-validated): the most events reaching more than 20 people one actor makes in a UTC day.
        broadcastsPerDay = requireConfigInt(
            environment.config, "notifications.maxBroadcastsPerActorPerDay", min = 1, max = MAX_BROADCASTS_PER_ACTOR,
        ),
    )
    // With no outbound email (`mail.transport=disabled`) nothing is mailed, so a notice the person would only have had by email
    // makes no row.
    // A thread-level notice still waiting for the digest is coalesced into only while it is younger than the digest delay (boot-validated
    // by the sweep, read again here for the writer).
    val digestDelayMillis = requireConfigInt(environment.config, "notifications.email.delaySeconds", min = 0, max = 86_400) * 1_000L
    val noticeWriter = NoticeWriter(emailBudget, mailAvailable = mailer() != null, digestDelayMillis = digestDelayMillis)
    val roomMemberService = RoomMemberService(database, teamService, noticeWriter)
    attributes.put(ThreadMuteServiceKey, ThreadMuteService(database, roomMemberService))
    attributes.put(RoomMemberServiceKey, roomMemberService)
    attributes.put(RoomEnrollmentServiceKey, RoomEnrollmentService(database, noticeWriter))
    attributes.put(NotificationServiceKey, NotificationService(database, roomService, emailBudget, notificationReadLimiter))
    // The watches (V26): the caller's own state, toggled without the room lock; the thread writes add the auto-watches inside themselves,
    // and the mention follower reads the watchers of a thread as the audience of a reply.
    val threadWatchService = ThreadWatchService(database, roomMemberService)
    attributes.put(ThreadWatchServiceKey, threadWatchService)
    // The mention index (V17) is the message write's first follower: it is built here and handed to the service, which runs it. The other
    // followers (V21: a deleted message loses its pin) run after it, inside the same write.
    val foregroundPresence = ForegroundPresence()
    attributes.put(ForegroundPresenceKey, foregroundPresence)
    val messageMentionService = MessageMentionService(roomMemberService, noticeWriter, threadWatchService, teamService, foregroundPresence)
    // Home is a read-only top-layer projection over rooms and threads. It shares the bounded, chat-read-bucketed list limiter.
    attributes.put(HomeServiceKey, HomeService(database, messageMentionService, readLimiter))
    // The reactions (V20) are read by the message reads and clear a deleted message's rows as a follower, which the service always runs.
    val reactionService = MessageReactionService(database)
    attributes.put(MessageReactionServiceKey, reactionService)
    val messageService = MessageService(
        database, roomService, messageTagService, messageMentionService,
        listOf(threadWatchService.replyFollower, PinCleanup()), reactionService, threadReadLimiter,
    )
    attributes.put(MessageServiceKey, messageService)
    attributes.put(MessageLinkPreviewServiceKey, MessageLinkPreviewService(roomService, messageService))
    attributes.put(PinServiceKey, PinService(database, messageService))
    // The thread's curated tags (V33): the list and the read put them on every thread; the PUT is a thread write under the room lock.
    val threadTagService = ThreadTagService(database, threadEventService)
    attributes.put(ThreadTagServiceKey, threadTagService)
    attributes.put(ThreadReadServiceKey, ThreadReadService(database, roomMemberService, noticeWriter))
    attributes.put(
        ThreadServiceKey,
        ThreadService(
            database,
            roomService,
            roomWorkflowService,
            roomMemberService,
            threadEventService,
            messageService,
            threadWatchService,
            teamService,
            messageTagService,
            threadTagService,
            ThreadReadLimiters(readLimiter, threadReadLimiter),
        ),
    )
    // The full-text search (V16): one read transaction under ONE `search.timeoutSeconds` deadline, at most `search.maxConcurrent` at once
    // (both boot-validated).
    attributes.put(
        ThreadSearchServiceKey,
        ThreadSearchService(
            database,
            roomService,
            threadTagService,
            requireConfigInt(environment.config, "search.timeoutSeconds", min = 1, max = 30),
            searchMaxConcurrent,
        ),
    )
    attributes.put(
        ThreadStatusServiceKey,
        ThreadStatusService(
            database, roomService, roomWorkflowService, roomMemberService, threadEventService, noticeWriter, threadWatchService,
        ),
    )
    // The workflow's thread-usage port (S6): rooms/ declares it and never imports threads/, so this root registers the implementation.
    attributes.put(WorkflowUsageKey, ThreadWorkflowUsage(roomWorkflowService, threadEventService))
}
