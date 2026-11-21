package ch.nokillswit

import ch.nokillswit.infra.revision.EXPECTED_REVISION_HEADER
import ch.nokillswit.tags.TagCategory
import ch.nokillswit.tags.TagCategoryList
import ch.nokillswit.tags.TagCategoryRequest
import ch.nokillswit.tags.TagCategoryService
import ch.nokillswit.threads.ThreadTagService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlin.test.assertEquals
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * Tag-registry fixtures (1.2.0 S6; Hammurabi's `TagFixtures.kt` without its ADR halves). The registry is SHARED state and a tag name is
 * unique for ever (retired rows included), so every test mints UNIQUE category names and tags ([categoryName], [uniqueTag]) and removes
 * what it made by a raw delete ([TestTags.purge]; a soft delete would keep the unique tag name): call [TestTags.purge] from an
 * `@AfterTest`. The seeded Kind and Area categories (V32) are never touched.
 */

/** A category name no other test uses (the active-name uniqueness is registry-wide and the registry is shared state). */
fun categoryName(prefix: String = "Category") = "$prefix ${UUID.randomUUID()}"

/** A tag no other test uses (a tag lives in ONE category, registry-wide, retired rows included). Short: tags are at most 40 characters. */
fun uniqueTag(label: String = "t") = "$label-${UUID.randomUUID().toString().take(8)}"

object TestTags {
    private val minted = ConcurrentLinkedQueue<UInt>()

    /** Remembers a category a test created through the routes, so [purge] can remove it. */
    fun mint(id: UInt) {
        minted.add(id)
    }

    /** The registry's own table (the cap test's direct inserts). */
    val categories = TagCategoryService.TagCategories

    /**
     * Removes every category [mint]ed so far with its tags, retired ones included. The foreign keys force the order: the threads' rows of
     * those tags first (`thread_tags.tag_id` has no `ON DELETE` rule, V33: a tag is only ever retired, never erased), then the tags, then
     * the categories. So a test may purge its tags before or after its rooms.
     */
    suspend fun purge() {
        val ids = generateSequence { minted.poll() }.toList()
        if (ids.isEmpty()) return
        suspendTransaction(sharedDatabaseForTests()) {
            val tags = TagCategoryService.Tags
            val tagIds = tags.select(tags.id).where { tags.categoryId inList ids }.map { it[tags.id].value }.toList()
            if (tagIds.isNotEmpty()) ThreadTagService.ThreadTags.deleteWhere { ThreadTagService.ThreadTags.tagId inList tagIds }
            TagCategoryService.Tags.deleteWhere { TagCategoryService.Tags.categoryId inList ids }
            categories.deleteWhere { categories.id inList ids }
        }
    }
}

/** POSTs a category as [this] (asserting 201) and returns it. */
suspend fun HttpClient.createCategory(
    tags: List<String> = listOf(uniqueTag()),
    name: String = categoryName(),
    description: String? = null,
): TagCategory {
    val response = postJson("/api/v1/tag-categories", TagCategoryRequest(name, description, tags))
    assertEquals(HttpStatusCode.Created, response.status, "create category: ${response.status}")
    return response.body<TagCategory>().also { TestTags.mint(it.id) }
}

/** POSTs a category (any status code); the defaults are a fresh name and one fresh tag. A created one is minted for the purge. */
suspend fun HttpClient.postCategory(name: String = categoryName(), tags: List<String> = listOf(uniqueTag())): HttpResponse =
    postJson("/api/v1/tag-categories", TagCategoryRequest(name, null, tags)).also { response ->
        if (response.status == HttpStatusCode.Created) TestTags.mint(response.body<TagCategory>().id)
    }

/** The stored revision of the category [id] as [this] reads it now (1 for an unknown id, so a 404 test still sends a valid header). */
suspend fun HttpClient.categoryRevision(id: UInt): Long =
    get("/api/v1/tag-categories").body<TagCategoryList>().items.firstOrNull { it.id == id }?.revision ?: 1L

/** Replaces the category [id] as [this] under [revision] (default: its current one; any status code). */
suspend fun HttpClient.saveCategory(
    id: UInt,
    tags: List<String>,
    name: String,
    description: String? = null,
    revision: Long? = null,
): HttpResponse = put("/api/v1/tag-categories/$id") {
    header(EXPECTED_REVISION_HEADER, (revision ?: categoryRevision(id)).toString())
    contentType(ContentType.Application.Json)
    setBody(TagCategoryRequest(name, description, tags))
}

/** Deletes the category [id] as [this] under [revision] (default: its current one; any status code). */
suspend fun HttpClient.deleteCategory(id: UInt, revision: Long? = null): HttpResponse = delete("/api/v1/tag-categories/$id") {
    header(EXPECTED_REVISION_HEADER, (revision ?: categoryRevision(id)).toString())
}

/** The live tags of every category [this] can read, flattened. */
suspend fun HttpClient.registryTags(): Set<String> =
    get("/api/v1/tag-categories").body<TagCategoryList>().items.flatMap { it.tags }.toSet()
