package ch.nokillswit

import io.ktor.client.HttpClient
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodedPath
import io.ktor.server.testing.testApplication
import java.net.URLEncoder
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * The per-route body ceilings (`plugins/Http.kt`) must meet a request however the ID segment is spelled, because the router accepts
 * more spellings than the plain digits: the typed resource parameters go through `String.toInt()`, which takes a leading `+`
 * (`%2B1`), leading zeros (`01`) and any Unicode decimal digit (the Arabic-Indic `١`, the fullwidth `１`). A ceiling regex written
 * for `[0-9]+` let each of those reach the 512 KiB global ceiling. Each spelling here is first shown to ROUTE (a small body is
 * answered `204` by the real handler), then to meet the small ceiling (an oversize body is `413`).
 */
class BodyLimitIdSpellingTest {
    private val minted = mutableListOf<UInt>()
    private val mintedRooms = mutableListOf<UInt>()
    private val mintedTemplates = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(mintedRooms)
        minted.forEach { TestUsers.softDelete(it) }
        mintedTemplates.forEach { TestTemplates.delete(it) }
    }

    private suspend fun HttpClient.rawEncoded(method: HttpMethod, encodedPath: String, json: String): HttpResponse = request {
        this.method = method
        url { this.encodedPath = encodedPath }
        contentType(ContentType.Application.Json)
        setBody(json)
    }

    /** [id] written in the decimal digits that start at code point [zero], percent-encoded as UTF-8. */
    private fun digits(id: UInt, zero: Int): String =
        id.toString().map { String(Character.toChars(zero + (it - '0'))) }.joinToString("") { URLEncoder.encode(it, Charsets.UTF_8) }

    private fun spellings(id: UInt): Map<String, String> = mapOf(
        "a leading plus" to "%2B$id",
        "a literal plus" to "+$id",
        "leading zeros" to "000$id",
        "plus and zeros" to "%2B00$id",
        "arabic-indic digits" to digits(id, 0x0660),
        "fullwidth digits" to digits(id, 0xFF10),
    )

    @Test
    fun `an id spelled with a plus, zeros or other digits meets the same ceiling as the plain one`() = testApplication {
        usePostgresTestcontainer()
        val owner = seedActor("bl-spell").also { minted += it.id }
        val template = TestThreads.seedTemplate().also { mintedTemplates += it }
        val room = TestRooms.seed(owner.id, template, "Spell ${UUID.randomUUID().toString().substring(0, 8)}").also { mintedRooms += it }
        TestThreads.seed(room, owner.id)
        val big = """{"status":"open",${" ".repeat(20 * 1024)}"x":1}"""
        val small = """{"status":"open"}"""
        for ((name, id) in spellings(room)) {
            val path = "/api/v1/rooms/$id/threads/1/status"
            assertEquals(HttpStatusCode.NoContent, owner.client.rawEncoded(HttpMethod.Put, path, small).status, "$name routes ($path)")
            val oversize = owner.client.rawEncoded(HttpMethod.Put, path, big)
            assertEquals(HttpStatusCode.PayloadTooLarge, oversize.status, "$name meets the ceiling")
        }
    }
}
