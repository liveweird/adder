package ch.nokillswit

import ch.nokillswit.infra.outbound.MAX_PREVIEW_IMAGE_DIMENSION
import ch.nokillswit.infra.outbound.MAX_PREVIEW_HTML_BYTES
import ch.nokillswit.infra.outbound.PreviewKind
import ch.nokillswit.infra.outbound.parsePreviewPage
import ch.nokillswit.infra.outbound.reencodePreviewImage
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreviewContentTest {
    @Test
    fun `extracts bounded plain metadata and resolves one og image without honoring base or refresh`() {
        val html = """
            <html><head>
              <base href="https://attacker.example/">
              <meta http-equiv="refresh" content="0; url=https://attacker.example/">
              <meta property="og:title" content="  Incident   report  ">
              <meta property="og:image" content="/images/summary.png">
              <title>Ignored title</title>
            </head><body><script>ignored()</script></body></html>
        """.trimIndent().toByteArray()

        val page = parsePreviewPage(html, "https://docs.example/path/report")

        assertEquals("Incident report", page.title)
        assertEquals("https://docs.example/images/summary.png", page.imageUrl)
    }

    @Test
    fun `falls back to title and rejects unsafe or unsupported thumbnail destinations`() {
        assertEquals(
            "Page title",
            parsePreviewPage("<title> Page\n title </title>".toByteArray(), "https://docs.example/").title,
        )
        assertNull(
            parsePreviewPage(
                "<meta property=og:image content='data:image/png;base64,AAAA'>".toByteArray(),
                "https://docs.example/",
            ).imageUrl,
        )
    }

    @Test
    fun `decodes dimensions before scaling and reencodes a stripped PNG`() {
        val source = BufferedImage(1_200, 600, BufferedImage.TYPE_INT_RGB)
        val graphics = source.createGraphics()
        graphics.color = Color(20, 80, 140)
        graphics.fillRect(0, 0, source.width, source.height)
        graphics.dispose()
        val encoded = ByteArrayOutputStream().also { ImageIO.write(source, "png", it) }.toByteArray()

        val preview = assertNotNull(reencodePreviewImage(encoded, "image/png"))

        assertEquals("image/png", preview.mediaType)
        assertEquals(MAX_PREVIEW_IMAGE_DIMENSION, preview.width)
        assertEquals(320, preview.height)
        val decoded = Base64.getDecoder().decode(preview.data)
        assertTrue(decoded.size <= 256 * 1024)
        assertEquals(640, ImageIO.read(decoded.inputStream()).width)
    }

    @Test
    fun `rejects unsupported formats corrupt images and decompression dimensions over the pixel cap`() {
        assertNull(reencodePreviewImage("<svg/>".toByteArray(), "image/svg+xml"))
        assertNull(reencodePreviewImage("not an image".toByteArray(), "image/png"))

        val oversized = BufferedImage(2_001, 2_001, BufferedImage.TYPE_BYTE_BINARY)
        val encoded = ByteArrayOutputStream().also { ImageIO.write(oversized, "png", it) }.toByteArray()
        assertNull(reencodePreviewImage(encoded, "image/png"))
    }

    @Test
    fun `content models expose only page image and unavailable result kinds`() {
        assertEquals(listOf("PAGE", "IMAGE", "UNAVAILABLE"), PreviewKind.entries.map { it.name })
    }

    @Test
    fun `bounds titles and rejects invalid preview metadata`() {
        assertNull(parsePreviewPage(ByteArray(MAX_PREVIEW_HTML_BYTES + 1), "https://docs.example/").title)
        assertNull(parsePreviewPage("<title>  </title>".toByteArray(), "https://docs.example/").title)
        val longTitle = "a".repeat(250)
        assertEquals(200, parsePreviewPage("<title>$longTitle</title>".toByteArray(), "https://docs.example/").title?.length)

        val destinations = listOf(
            "http://cdn.example/image.png",
            "https://user:secret@cdn.example/image.png",
            "https://cdn.example:8443/image.png",
            "http://[",
        )
        destinations.forEach { destination ->
            val html = "<meta property=og:image content='$destination'>".toByteArray()
            assertNull(parsePreviewPage(html, "https://docs.example/").imageUrl)
        }
        assertNull(parsePreviewPage("<meta property=og:image content=/image>".toByteArray(), "not a url").imageUrl)
    }

    @Test
    fun `reencodes JPEG and rejects empty and over-budget inputs`() {
        val source = BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB)
        val jpeg = ByteArrayOutputStream().also { ImageIO.write(source, "jpeg", it) }.toByteArray()

        val preview = assertNotNull(reencodePreviewImage(jpeg, "image/jpeg"))

        assertEquals(40, preview.width)
        assertEquals(20, preview.height)
        assertNull(reencodePreviewImage(byteArrayOf(), "image/png"))
        assertNull(reencodePreviewImage(ByteArray(2 * 1024 * 1024 + 1), "image/png"))
    }
}
