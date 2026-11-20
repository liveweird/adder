package ch.nokillswit

import ch.nokillswit.threads.MAX_STORED_TAG_LENGTH
import ch.nokillswit.threads.MAX_TAG_LENGTH
import ch.nokillswit.threads.MessageRequest
import ch.nokillswit.threads.MessageResponse
import ch.nokillswit.threads.RoomTagPageResponse
import ch.nokillswit.threads.TAGS_VERSION
import ch.nokillswit.threads.ThreadCreateRequest
import ch.nokillswit.threads.ThreadPageResponse
import ch.nokillswit.threads.ThreadResponse
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.net.URLEncoder
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

/**
 * The hashtag index over HTTP (`chat-model.md` "Hashtags (V15)", 0.5 D3): a tag belongs to a MESSAGE, so a thread has it while one of its
 * LIVE messages does. A post, a thread's opening message, an edit and a delete (a moderator's too) each keep `message_tags` in step in
 * the message's own transaction; the thread list's `tag` filter and `GET /rooms/{id}/tags` read it. The parser is `HashtagsTest`, the
 * boot reindex `MessageTagReindexTest`. Rooms, templates and users are SHARED suite state: every test mints UNIQUE rows and removes them.
 */
class MessageTagsTest {
    private val rooms = "/api/v1/rooms"
    private val mintedRooms = mutableListOf<UInt>()
    private val mintedUsers = mutableListOf<UInt>()
    private val mintedTemplates = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(mintedRooms)
        mintedUsers.forEach { TestUsers.softDelete(it) }
        mintedTemplates.forEach { TestTemplates.delete(it) }
    }

    private val minted = MintedIds(mintedUsers, mintedTemplates, mintedRooms)

    private suspend fun ApplicationTestBuilder.cast(prefix: String): RoomCast = seedRoomCast(prefix, "Tag", minted)

    private fun encoded(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

    private suspend fun HttpClient.post(room: UInt, number: Int, body: String): MessageResponse {
        val response = postJson("$rooms/$room/threads/$number/messages", MessageRequest(body))
        assertEquals(HttpStatusCode.Created, response.status)
        return response.body()
    }

    private suspend fun HttpClient.edit(room: UInt, id: UInt, body: String) {
        assertEquals(HttpStatusCode.NoContent, putJson("$rooms/$room/messages/$id", MessageRequest(body)).status)
    }

    private suspend fun HttpClient.threadNumbers(room: UInt, query: String): List<Int> =
        get("$rooms/$room/threads$query").body<ThreadPageResponse>().items.map { it.number }.sorted()

    private suspend fun HttpClient.tags(room: UInt, query: String = ""): RoomTagPageResponse = get("$rooms/$room/tags$query").body()

    private fun tagsOf(messageId: UInt): List<String> = PostgresTestSupport.connect().use { c ->
        c.prepareStatement("SELECT tag FROM message_tags WHERE message_id = ? ORDER BY tag").use { st ->
            st.setInt(1, messageId.toInt())
            st.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
        }
    }

    private fun tagRowCount(threadId: UInt): Int = PostgresTestSupport.connect().use { c ->
        c.prepareStatement("SELECT count(*) FROM message_tags WHERE thread_id = ?").use { st ->
            st.setInt(1, threadId.toInt())
            st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    private fun rowVersions(messageId: UInt): String = PostgresTestSupport.connect().use { c ->
        val sql = "SELECT coalesce(string_agg(xmin::text, ',' ORDER BY tag), '') FROM message_tags WHERE message_id = ?"
        c.prepareStatement(sql).use { st ->
            st.setInt(1, messageId.toInt())
            st.executeQuery().use { rs -> rs.next(); rs.getString(1) }
        }
    }

    private fun tagsVersionOf(messageId: UInt): Int = PostgresTestSupport.connect().use { c ->
        c.prepareStatement("SELECT tags_version FROM messages WHERE id = ?").use { st ->
            st.setInt(1, messageId.toInt())
            st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    @Test
    fun `a posted message's hashtags are indexed, folded by case and accent`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("tag-post")
        val thread = TestThreads.seed(cast.room, cast.member.id)
        val message = cast.member.client.post(cast.room, 1, "Rollout #Wdrożenie done, #WDROZENIE again, also #Zażółć and #db-outage")
        assertEquals(listOf("db-outage", "wdrozenie", "zazolc"), tagsOf(message.id))
        assertEquals(TAGS_VERSION.toInt(), tagsVersionOf(message.id))
        assertEquals(0, tagsOf(thread.messageId).size, "the opening message of the fixture has no tag")
        assertEquals(emptyList(), tagsOf(cast.member.client.post(cast.room, 1, "no tag, #42 or code `#nope`").id))
    }

    @Test
    fun `a thread's opening message is indexed too`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("tag-open")
        val response = cast.member.client.postJson(
            "$rooms/${cast.room}/threads",
            ThreadCreateRequest("Opening", "Start with #Kickoff and #Łódź", mapOf("severity" to JsonPrimitive("s1"))),
        )
        assertEquals(HttpStatusCode.Created, response.status)
        val thread = response.body<ThreadResponse>()
        val opener = TestThreads.rawMessages(thread.id).single()
        assertEquals(listOf("kickoff", "lodz"), tagsOf(opener.id))
        assertEquals(listOf(thread.number), cast.owner.client.threadNumbers(cast.room, "?tag=kickoff"))
    }

    @Test
    fun `an edit replaces the tags and an unchanged edit writes nothing`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("tag-edit")
        TestThreads.seed(cast.room, cast.member.id)
        val message = cast.member.client.post(cast.room, 1, "first #alpha #beta")
        assertEquals(listOf("alpha", "beta"), tagsOf(message.id))
        val before = rowVersions(message.id)

        // Other words, the same tags (and a different case): the folded set is unchanged, so no tag row is rewritten.
        cast.member.client.edit(cast.room, message.id, "reworded #Beta and #ALPHA")
        assertEquals(before, rowVersions(message.id), "an edit that keeps the folded set rewrites no tag row")
        // The very same body again is a no-op (204, no write at all).
        cast.member.client.edit(cast.room, message.id, "reworded #Beta and #ALPHA")
        assertEquals(before, rowVersions(message.id))

        // A changed set: the old rows go, the new ones come.
        cast.member.client.edit(cast.room, message.id, "now #beta and #gamma")
        assertEquals(listOf("beta", "gamma"), tagsOf(message.id))
        cast.member.client.edit(cast.room, message.id, "no tags any more")
        assertEquals(emptyList(), tagsOf(message.id))
        assertEquals(emptyList(), cast.owner.client.threadNumbers(cast.room, "?tag=beta"))
    }

    @Test
    fun `an edit of a message the index never saw rewrites its tags even when the set looks unchanged`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("tag-stale")
        TestThreads.seed(cast.room, cast.member.id)
        val message = cast.member.client.post(cast.room, 1, "old #alpha")
        // A message from before the index: version 0 and no row (the boot reindex has not reached it).
        PostgresTestSupport.connect().use { c ->
            c.createStatement().use { it.executeUpdate("DELETE FROM message_tags WHERE message_id = ${message.id}") }
            c.createStatement().use { it.executeUpdate("UPDATE messages SET tags_version = 0 WHERE id = ${message.id}") }
        }
        cast.member.client.edit(cast.room, message.id, "reworded #alpha")
        assertEquals(listOf("alpha"), tagsOf(message.id))
        assertEquals(TAGS_VERSION.toInt(), tagsVersionOf(message.id))
    }

    @Test
    fun `a deleted message loses its tags, a moderator's delete included`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("tag-del")
        val thread = TestThreads.seed(cast.room, cast.member.id)
        val own = cast.member.client.post(cast.room, 1, "mine #alpha")
        val other = cast.member.client.post(cast.room, 1, "theirs #beta #gamma")
        assertEquals(listOf(1, 2), listOf(tagsOf(own.id).size, tagsOf(other.id).size))

        assertEquals(HttpStatusCode.NoContent, cast.member.client.delete("$rooms/${cast.room}/messages/${own.id}").status)
        assertEquals(emptyList(), tagsOf(own.id), "the author's delete")
        assertEquals(HttpStatusCode.NoContent, cast.owner.client.delete("$rooms/${cast.room}/messages/${other.id}").status)
        assertEquals(emptyList(), tagsOf(other.id), "the room OWNER's delete")
        assertEquals(0, tagRowCount(thread.id))
        assertEquals(emptyList(), cast.owner.client.threadNumbers(cast.room, "?tag=alpha&tag=beta&tag=gamma"))
        assertEquals(0L, cast.owner.client.tags(cast.room).total, "the tag list forgets them too")
    }

    @Test
    fun `the tag filter finds a thread through any live message`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("tag-find")
        repeat(3) { TestThreads.seed(cast.room, cast.member.id) }
        cast.member.client.post(cast.room, 1, "alpha lives in #Wdrożenie")
        cast.member.client.post(cast.room, 2, "#alpha and #beta")
        val gone = cast.member.client.post(cast.room, 3, "#alpha")
        cast.member.client.delete("$rooms/${cast.room}/messages/${gone.id}")
        TestThreads.closeRaw(cast.room, 2)

        val owner = cast.owner.client
        assertEquals(
            listOf(2), owner.threadNumbers(cast.room, "?tag=alpha"),
            "a deleted message's tag no longer tags thread 3; closed threads are found too",
        )
        assertEquals(listOf(1), owner.threadNumbers(cast.room, "?tag=WDROZENIE"), "folded by case and accent")
        assertEquals(listOf(1), owner.threadNumbers(cast.room, "?tag=${encoded("wdrożenie")}"))
        assertEquals(listOf(1, 2), owner.threadNumbers(cast.room, "?tag=wdrozenie&tag=beta"), "any-of")
        assertEquals(emptyList(), owner.threadNumbers(cast.room, "?tag=nothing-like-this"))
        assertEquals(listOf(1), owner.threadNumbers(cast.room, "?tag=wdrozenie&tag=beta&closed=false"), "combined with closed")
        assertEquals(1L, owner.get("$rooms/${cast.room}/threads?tag=wdrozenie").body<ThreadPageResponse>().total)
    }

    @Test
    fun `two spellings are one tag`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("tag-one")
        repeat(2) { TestThreads.seed(cast.room, cast.member.id) }
        val message = cast.member.client.post(cast.room, 1, "#Wdrożenie and #wdrozenie and #WDROŻENIE in one message")
        cast.member.client.post(cast.room, 2, "elsewhere #wdrozenie")
        assertEquals(listOf("wdrozenie"), tagsOf(message.id), "three spellings, one row")
        val tags = cast.owner.client.tags(cast.room)
        assertEquals(listOf("wdrozenie" to 2L), tags.items.map { it.tag to it.threadCount })
        assertEquals(listOf(1, 2), cast.owner.client.threadNumbers(cast.room, "?tag=Wdrożenie"))
    }

    @Test
    fun `a tag whose folding is longer than the column is cut, not refused`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("tag-long")
        TestThreads.seed(cast.room, cast.member.id)
        // Fifty characters that unaccent turns into five each (U+2152 becomes " 1/10"): 246 characters before the cut.
        val tag = "a" + "⅒".repeat(49)
        val message = cast.member.client.post(cast.room, 1, "long #$tag end")
        assertEquals(listOf(MAX_STORED_TAG_LENGTH), tagsOf(message.id).map { it.length })
        val found = cast.owner.client.threadNumbers(cast.room, "?tag=${encoded(tag)}")
        assertEquals(listOf(1), found, "the filter folds and cuts the same way")
    }

    @Test
    fun `a stored key never starts or ends with a space, and every key of the tag list round-trips`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("tag-space")
        repeat(2) { TestThreads.seed(cast.room, cast.member.id) }
        // `unaccent` turns a vulgar fraction into text with a LEADING space (` 1/2`), and a cut at 150 characters can land on one: four
        // letters then 46 times ` 1/10` put a space exactly at the 150th character.
        val cutOnSpace = "abcd" + "\u2152".repeat(46)
        cast.member.client.post(cast.room, 1, "price #\u00bdprice and #$cutOnSpace")
        cast.member.client.post(cast.room, 2, "#a\u00bd")
        val keys = cast.owner.client.tags(cast.room).items.map { it.tag }
        assertTrue(keys.none { it != it.trim() }, "a key is trimmed: $keys")
        assertTrue("1/2price" in keys && "a 1/2" in keys, "the fraction folds to text without its leading space: $keys")
        assertEquals(MAX_STORED_TAG_LENGTH - 1, keys.single { it.startsWith("abcd") }.length, "the cut ended on a space, now trimmed")
        // What the picker sends back is what it was given, trimming included, and it finds its thread.
        for (key in keys) {
            assertEquals(HttpStatusCode.OK, cast.owner.client.get("$rooms/${cast.room}/threads?tag=${encoded(key)}").status, key)
        }
        assertEquals(listOf(1), cast.owner.client.threadNumbers(cast.room, "?tag=${encoded("1/2price")}"))
        assertEquals(listOf(1), cast.owner.client.threadNumbers(cast.room, "?tag=${encoded("\u00bdprice")}"), "as typed")
        assertEquals(listOf(1), cast.owner.client.threadNumbers(cast.room, "?tag=${encoded(cutOnSpace)}"), "as typed, cut and trimmed")
        assertEquals(listOf(2), cast.owner.client.threadNumbers(cast.room, "?tag=${encoded("a 1/2")}"), "an inner space is kept")
    }

    @Test
    fun `the room's tag list counts threads, pages, sorts and filters by substring`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("tag-list")
        repeat(3) { TestThreads.seed(cast.room, cast.member.id) }
        val member = cast.member.client
        member.post(cast.room, 1, "#popular #Zebra")
        member.post(cast.room, 1, "again #popular")
        member.post(cast.room, 2, "#popular #rare")
        member.post(cast.room, 3, "#popular #Zażółć")
        TestThreads.closeRaw(cast.room, 3)

        val owner = cast.owner.client
        val byCount = owner.tags(cast.room)
        assertEquals(
            listOf("popular" to 3L, "rare" to 1L, "zazolc" to 1L, "zebra" to 1L),
            byCount.items.map { it.tag to it.threadCount },
            "default -threadCount, ties by tag; distinct threads (two messages of thread 1 count once), the closed one included",
        )
        assertEquals(4L, byCount.total)
        assertEquals(listOf("zebra", "zazolc", "rare", "popular"), owner.tags(cast.room, "?sort=-tag").items.map { it.tag })
        assertEquals(listOf("rare", "zazolc", "zebra", "popular"), owner.tags(cast.room, "?sort=threadCount").items.map { it.tag })
        val second = owner.tags(cast.room, "?pageSize=2&page=2")
        assertEquals(listOf("zazolc", "zebra"), second.items.map { it.tag })
        assertEquals(4L, second.total)
        val folded = owner.tags(cast.room, "?q=${encoded("ZA\u017b")}").items.map { it.tag }
        assertEquals(listOf("zazolc"), folded, "an accent- and case-insensitive substring")
        assertEquals(listOf("zazolc", "zebra"), owner.tags(cast.room, "?q=z&sort=tag").items.map { it.tag })
        assertEquals(1L, owner.tags(cast.room, "?q=z&pageSize=1").items.size.toLong())
        assertEquals(0L, owner.tags(cast.room, "?q=nothing").total)
        assertEquals(listOf("popular"), member.tags(cast.room, "?q=pop").items.map { it.tag }, "any member reads it")
        for (bad in listOf("?q=${"a".repeat(51)}", "?sort=id", "?sort=-name", "?page=0", "?pageSize=101", "?q=a&q=b")) {
            assertEquals(HttpStatusCode.BadRequest, owner.get("$rooms/${cast.room}/tags$bad").status, bad)
        }
    }

    @Test
    fun `a key from the tag list is accepted by the tag filter, even where folding added punctuation or length`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("tag-key")
        repeat(3) { TestThreads.seed(cast.room, cast.member.id) }
        // `\u02bb` (a letter) unaccents to an apostrophe, `\u00df` to `ss`: the stored keys are not tag-shaped, and the second is 52 long.
        val hawaii = "Hawai\u02bbi"
        val doubleSharp = "\u00df\u00df" + "a".repeat(MAX_TAG_LENGTH - 2)
        cast.member.client.post(cast.room, 1, "aloha #$hawaii")
        cast.member.client.post(cast.room, 2, "long #$doubleSharp")
        cast.member.client.post(cast.room, 3, "plain #plain")
        val keys = cast.owner.client.tags(cast.room).items.map { it.tag }
        val hawaiiKey = keys.single { it.startsWith("hawai") }
        val longKey = keys.single { it.length > MAX_TAG_LENGTH }
        assertEquals("hawai'i", hawaiiKey)
        assertEquals(MAX_TAG_LENGTH + 2, longKey.length, "two sharp s folded to four letters")
        // What the picker sends back is exactly what it was given: the round trip holds for every key the list returns.
        for (key in keys) {
            assertEquals(HttpStatusCode.OK, cast.owner.client.get("$rooms/${cast.room}/threads?tag=${encoded(key)}").status, key)
        }
        assertEquals(listOf(1), cast.owner.client.threadNumbers(cast.room, "?tag=${encoded(hawaiiKey)}"))
        assertEquals(listOf(2), cast.owner.client.threadNumbers(cast.room, "?tag=${encoded(longKey)}"))
        // The spelling a person types still finds them too.
        assertEquals(listOf(1), cast.owner.client.threadNumbers(cast.room, "?tag=${encoded(hawaii.uppercase())}"))
        assertEquals(listOf(2), cast.owner.client.threadNumbers(cast.room, "?tag=${encoded(doubleSharp)}"))
        assertEquals(listOf(1, 2), cast.owner.client.threadNumbers(cast.room, "?tag=${encoded(hawaiiKey)}&tag=${encoded(longKey)}"))
    }

    @Test
    fun `the tag list and the tag filter never read another room`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("tag-iso")
        val other = cast("tag-iso2")
        TestThreads.seed(cast.room, cast.member.id)
        repeat(2) { TestThreads.seed(other.room, other.member.id) }
        cast.member.client.post(cast.room, 1, "#popular #mine")
        other.member.client.post(other.room, 1, "#popular #theirs")
        other.member.client.post(other.room, 2, "#popular")

        val mine = cast.owner.client.tags(cast.room)
        assertEquals(listOf("mine" to 1L, "popular" to 1L), mine.items.map { it.tag to it.threadCount }.sortedBy { it.first })
        assertEquals(2L, mine.total, "another room's tag is not counted")
        val theirs = other.owner.client.tags(other.room).items.map { it.tag to it.threadCount }.sortedBy { it.first }
        assertEquals(listOf("popular" to 2L, "theirs" to 1L), theirs)
        assertEquals(listOf(1), cast.owner.client.threadNumbers(cast.room, "?tag=popular"))
        assertEquals(emptyList(), cast.owner.client.threadNumbers(cast.room, "?tag=theirs"))
        assertEquals(emptyList(), cast.owner.client.tags(cast.room, "?q=theirs").items)
    }

    @Test
    fun `a malformed tag filter is 400, the bound counts after folding, and a non-member is 404 first`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("tag-bad")
        TestThreads.seed(cast.room, cast.member.id)
        val owner = cast.owner.client
        for (bad in listOf("%23a", "x%23y", "a".repeat(151), "a%00b", "a%0Ab")) {
            assertEquals(HttpStatusCode.BadRequest, owner.get("$rooms/${cast.room}/threads?tag=$bad").status, bad)
        }
        val eleven = (1..11).joinToString("&") { "tag=t$it" }
        assertEquals(HttpStatusCode.BadRequest, owner.get("$rooms/${cast.room}/threads?$eleven").status)
        // The bound of ten counts the values AFTER folding: twelve spellings of six tags are fine.
        val folded = (1..6).joinToString("&") { "tag=t$it&tag=T$it" }
        assertEquals(HttpStatusCode.OK, owner.get("$rooms/${cast.room}/threads?$folded").status, "12 raw values, 6 after folding")
        assertEquals(HttpStatusCode.OK, owner.get("$rooms/${cast.room}/threads?" + (1..10).joinToString("&") { "tag=t$it" }).status)
        assertEquals(HttpStatusCode.OK, owner.get("$rooms/${cast.room}/threads?tag=a&tag=A&tag=a").status, "duplicates collapse")
        assertEquals(HttpStatusCode.NotFound, cast.outsider.client.get("$rooms/${cast.room}/threads?tag=%23bad").status)
    }

    @Test
    fun `a non-member is 404 on the tag list`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("tag-404")
        TestThreads.seed(cast.room, cast.member.id)
        cast.member.client.post(cast.room, 1, "#secret")
        assertEquals(HttpStatusCode.NotFound, cast.outsider.client.get("$rooms/${cast.room}/tags").status)
        val tooLong = cast.outsider.client.get("$rooms/${cast.room}/tags?q=${"a".repeat(80)}")
        assertEquals(HttpStatusCode.NotFound, tooLong.status, "404 before 400")
        assertEquals(HttpStatusCode.NotFound, cast.outsider.client.get("$rooms/999999999/tags").status)
        assertTrue(cast.owner.client.tags(cast.room).items.any { it.tag == "secret" })
        // An ADMIN reads every room, a member or not (the matrix of `authorization.md`).
        val admin = seedActor("tag-404-adm", UserRole.ADMIN).also { mintedUsers += it.id }
        assertTrue(admin.client.tags(cast.room).items.any { it.tag == "secret" })
    }
}
