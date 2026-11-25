package ch.nokillswit.infra.outbound

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageInputStream
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup

internal const val MAX_PREVIEW_HTML_BYTES = 256 * 1024
internal const val MAX_PREVIEW_IMAGE_BYTES = 2 * 1024 * 1024
internal const val MAX_PREVIEW_IMAGE_PIXELS = 4_000_000L
internal const val MAX_PREVIEW_IMAGE_DIMENSION = 640
internal const val MAX_PREVIEW_OUTPUT_BYTES = 256 * 1024
private const val MAX_PREVIEW_TITLE_CODE_POINTS = 200

internal fun parsePreviewPage(bytes: ByteArray, finalUrl: String, charset: String = Charsets.UTF_8.name()): PreviewPage {
    if (bytes.size > MAX_PREVIEW_HTML_BYTES) return PreviewPage(null, null)
    val document = Jsoup.parse(ByteArrayInputStream(bytes), charset, finalUrl)
    val title = document.selectFirst("meta[property=og:title]")?.attr("content")
        ?.takeIf(String::isNotBlank) ?: document.title().takeIf(String::isNotBlank)
    val image = document.selectFirst("meta[property=og:image]")?.attr("content")
        ?.takeIf(String::isNotBlank)
        ?.let { resolvePublicHttpsReference(finalUrl, it) }
    return PreviewPage(normalizeTitle(title), image)
}

private fun normalizeTitle(raw: String?): String? {
    val normalized = raw?.replace(WHITESPACE, " ")?.trim().orEmpty()
    if (normalized.isEmpty()) return null
    val points = normalized.codePoints().limit(MAX_PREVIEW_TITLE_CODE_POINTS.toLong()).toArray()
    return String(points, 0, points.size)
}

private fun resolvePublicHttpsReference(base: String, reference: String): String? {
    val baseUrl = base.toHttpUrlOrNull() ?: return null
    val resolved = baseUrl.resolve(reference) ?: return null
    return resolved.toString().takeIf {
        resolved.isHttps && resolved.port == 443 && resolved.username.isEmpty() && resolved.password.isEmpty()
    }
}

internal fun reencodePreviewImage(bytes: ByteArray, mediaType: String): PreviewImage? {
    if (bytes.isEmpty() || bytes.size > MAX_PREVIEW_IMAGE_BYTES || mediaType !in SUPPORTED_IMAGES) return null
    return try {
        reencodeValidatedImage(bytes, mediaType)
    } catch (_: java.io.IOException) {
        null
    } catch (_: RuntimeException) {
        null
    }
}

private fun reencodeValidatedImage(bytes: ByteArray, mediaType: String): PreviewImage? {
    val format = if (mediaType == "image/png") "png" else "jpeg"
    val input = MemoryCacheImageInputStream(ByteArrayInputStream(bytes))
    input.use {
        val readers = ImageIO.getImageReadersByFormatName(format)
        if (!readers.hasNext()) return null
        val reader = readers.next()
        try {
            reader.input = input
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            if (width <= 0 || height <= 0 || width.toLong() * height > MAX_PREVIEW_IMAGE_PIXELS) return null
            val source = reader.read(0) ?: return null
            val scaled = scale(source, width, height, mediaType)
            val encoded = encode(scaled, format) ?: return null
            if (encoded.size > MAX_PREVIEW_OUTPUT_BYTES) return null
            return PreviewImage(mediaType, Base64.getEncoder().encodeToString(encoded), scaled.width, scaled.height)
        } finally {
            reader.dispose()
        }
    }
}

private fun scale(source: BufferedImage, width: Int, height: Int, mediaType: String): BufferedImage {
    val factor = minOf(1.0, MAX_PREVIEW_IMAGE_DIMENSION.toDouble() / maxOf(width, height))
    val targetWidth = maxOf(1, (width * factor).toInt())
    val targetHeight = maxOf(1, (height * factor).toInt())
    val type = if (mediaType == "image/png") BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
    return BufferedImage(targetWidth, targetHeight, type).also { target ->
        val graphics = target.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null)
        } finally {
            graphics.dispose()
        }
    }
}

private fun encode(image: BufferedImage, format: String): ByteArray? {
    val output = ByteArrayOutputStream()
    if (format == "png") return output.takeIf { ImageIO.write(image, format, it) }?.toByteArray()
    val writers = ImageIO.getImageWritersByFormatName(format)
    if (!writers.hasNext()) return null
    val writer = writers.next()
    return try {
        ImageIO.createImageOutputStream(output).use { destination ->
            writer.output = destination
            val parameters = writer.defaultWriteParam
            parameters.compressionMode = ImageWriteParam.MODE_EXPLICIT
            parameters.compressionQuality = 0.82f
            writer.write(null, javax.imageio.IIOImage(image, null, null), parameters)
        }
        output.toByteArray()
    } finally {
        writer.dispose()
    }
}

internal val SUPPORTED_IMAGES = setOf("image/png", "image/jpeg")
private val WHITESPACE = Regex("\\s+")
