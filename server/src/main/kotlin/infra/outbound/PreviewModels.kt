package ch.nokillswit.infra.outbound

internal fun interface PreviewFetcher {
    suspend fun fetch(url: String): PreviewContent?
}

internal data class PreviewContent(
    val kind: PreviewKind,
    val finalUrl: String,
    val title: String?,
    val image: PreviewImage?,
    val blockedThumbnail: Boolean = false,
)

internal enum class PreviewKind { PAGE, IMAGE, UNAVAILABLE }

internal data class PreviewImage(
    val mediaType: String,
    val data: String,
    val width: Int,
    val height: Int,
)

internal data class PreviewPage(val title: String?, val imageUrl: String?)
