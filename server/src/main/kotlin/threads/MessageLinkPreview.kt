package ch.nokillswit.threads

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.authz.TooManyRequestsException
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.infra.outbound.BlockedPreviewUrlException
import ch.nokillswit.infra.outbound.PreviewContent
import ch.nokillswit.infra.outbound.PreviewFetcher
import ch.nokillswit.infra.outbound.PreviewKind
import ch.nokillswit.plugins.RateLimitAuditThrottle
import ch.nokillswit.rooms.RoomService
import ch.nokillswit.rooms.visibleStanding
import io.ktor.server.plugins.BadRequestException
import io.ktor.util.AttributeKey
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.parser.Parser

internal const val MAX_LINK_PREVIEW_URL_LENGTH = 2_048
private const val LINK_PREVIEW_RETRY_AFTER_SECONDS = 1
private const val INVALID_LINK_PREVIEW_URL = "url must identify a current link in the message"

val MessageLinkPreviewServiceKey = AttributeKey<MessageLinkPreviewService>("MessageLinkPreviewService")

@Serializable
data class LinkPreviewRequest(val url: String)

@Serializable
enum class LinkPreviewKind { PAGE, IMAGE, UNAVAILABLE }

@Serializable
data class LinkPreviewImageResponse(
    val mediaType: String,
    val data: String,
    val width: Int,
    val height: Int,
)

@Serializable
data class LinkPreviewResponse(
    val kind: LinkPreviewKind,
    val url: String,
    val finalUrl: String?,
    val title: String?,
    val image: LinkPreviewImageResponse?,
)

/**
 * The message-scoped link-preview coordinator. The two message reads and room checks are intentionally separate from the external
 * fetch: no database connection or row lock is held while the remote site is contacted. Repeating both checks afterwards prevents a
 * late result from revealing content after access was revoked, the message was deleted, or its link was edited away.
 */
class MessageLinkPreviewService internal constructor(
    private val rooms: RoomService,
    private val messages: MessageService,
    private val blockedAudit: RateLimitAuditThrottle = RateLimitAuditThrottle(),
) {
    private val activeCallers = ConcurrentHashMap.newKeySet<UInt>()

    /** Runs before the route consumes JSON, preserving the uniform room/message 404 ahead of malformed-body 400s. */
    internal suspend fun authorize(caller: CallerPrincipal, roomId: UInt, messageId: UInt): LinkPreviewAuthorization {
        rooms.visibleStanding(caller, roomId)
        return LinkPreviewAuthorization(caller, roomId, messageId, messages.bodyOf(roomId, messageId).orNotFound("Message"))
    }

    internal suspend fun preview(
        authorization: LinkPreviewAuthorization,
        request: LinkPreviewRequest,
        fetcher: PreviewFetcher,
    ): LinkPreviewResponse {
        val caller = authorization.caller
        val roomId = authorization.roomId
        val messageId = authorization.messageId
        val body = authorization.body
        val canonical = canonicalMessageUrl(request.url)
        requireCurrentLink(body, canonical)
        return fetchAndRecheck(caller, roomId, messageId, canonical, fetcher)
    }

    private suspend fun fetchAndRecheck(
        caller: CallerPrincipal,
        roomId: UInt,
        messageId: UInt,
        canonical: String,
        fetcher: PreviewFetcher,
    ): LinkPreviewResponse {
        if (!activeCallers.add(caller.userId)) {
            throw TooManyRequestsException(
                "A link preview is already running - retry in a moment",
                retryAfterSeconds = LINK_PREVIEW_RETRY_AFTER_SECONDS,
            )
        }
        try {
            val content = if (canonical.toHttpUrlOrNull()?.isHttps == true) {
                try {
                    fetcher.fetch(canonical)
                } catch (_: BlockedPreviewUrlException) {
                    auditBlocked(caller, roomId, messageId, "primary")
                    null
                }
            } else {
                null
            }
            rooms.visibleStanding(caller, roomId)
            val currentBody = messages.bodyOf(roomId, messageId).orNotFound("Message")
            requireCurrentLink(currentBody, canonical)
            if (content?.blockedThumbnail == true) auditBlocked(caller, roomId, messageId, "thumbnail")
            return content.toResponse(canonical)
        } finally {
            activeCallers.remove(caller.userId)
        }
    }

    private fun auditBlocked(caller: CallerPrincipal, roomId: UInt, messageId: UInt, stage: String) {
        blockedAudit.admit("outbound.blocked:${caller.userId}:$stage")?.let { suppressed ->
            audit(
                "outbound.blocked",
                "byUserId" to caller.userId.toLong(),
                "roomId" to roomId.toLong(),
                "messageId" to messageId.toLong(),
                "stage" to stage,
                "suppressed" to suppressed,
            )
        }
    }
}

internal data class LinkPreviewAuthorization(
    val caller: CallerPrincipal,
    val roomId: UInt,
    val messageId: UInt,
    val body: String,
)

internal fun messageLinkDestinations(markdown: String): Set<String> {
    val destinations = linkedSetOf<String>()
    val document = LINK_PARSER.parse(markdown)
    document.accept(object : AbstractVisitor() {
        override fun visit(link: Link) {
            canonicalMessageUrlOrNull(link.destination)?.let(destinations::add)
            visitChildren(link)
        }

        override fun visit(image: Image) {
            canonicalMessageUrlOrNull(image.destination)?.let(destinations::add)
            visitChildren(image)
        }
    })
    return destinations
}

private fun canonicalMessageUrl(raw: String): String {
    if (raw.isBlank() || raw.length > MAX_LINK_PREVIEW_URL_LENGTH) throw BadRequestException(INVALID_LINK_PREVIEW_URL)
    return canonicalMessageUrlOrNull(raw) ?: throw BadRequestException(INVALID_LINK_PREVIEW_URL)
}

private fun canonicalMessageUrlOrNull(raw: String): String? {
    if (raw.length > MAX_LINK_PREVIEW_URL_LENGTH || raw.any { it.isWhitespace() || it.isISOControl() }) return null
    val url = raw.toHttpUrlOrNull() ?: return null
    val canonical = url.toString()
    return canonical.takeIf {
        canonical.length <= MAX_LINK_PREVIEW_URL_LENGTH && (url.scheme == "http" || url.scheme == "https")
    }
}

private fun requireCurrentLink(body: String, canonical: String) {
    if (canonical !in messageLinkDestinations(body)) throw BadRequestException(INVALID_LINK_PREVIEW_URL)
}

private fun PreviewContent?.toResponse(requestUrl: String): LinkPreviewResponse {
    if (this == null || kind == PreviewKind.UNAVAILABLE) {
        return LinkPreviewResponse(LinkPreviewKind.UNAVAILABLE, requestUrl, null, null, null)
    }
    val responseKind = when (kind) {
        PreviewKind.PAGE -> LinkPreviewKind.PAGE
        PreviewKind.IMAGE -> LinkPreviewKind.IMAGE
        PreviewKind.UNAVAILABLE -> LinkPreviewKind.UNAVAILABLE
    }
    return LinkPreviewResponse(
        kind = responseKind,
        url = requestUrl,
        finalUrl = finalUrl,
        title = title,
        image = image?.let { LinkPreviewImageResponse(it.mediaType, it.data, it.width, it.height) },
    )
}

private val LINK_PARSER: Parser = Parser.builder().extensions(listOf(AutolinkExtension.create())).build()
