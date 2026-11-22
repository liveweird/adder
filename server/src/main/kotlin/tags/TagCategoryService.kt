package ch.nokillswit.tags

import ch.nokillswit.authz.ConflictException
import ch.nokillswit.infra.db.SoftDeletable
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.collateC
import ch.nokillswit.infra.db.lockingTransaction
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.SortField
import ch.nokillswit.infra.paging.applyPaging
import ch.nokillswit.infra.revision.requireRevision
import io.ktor.server.plugins.BadRequestException
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

val TagCategoryServiceKey = AttributeKey<TagCategoryService>("TagCategoryService")

/** The lock every registry write runs under (`persistence.md` "Tag categories (V32)"); ordinary SELECTs stay available meanwhile. */
private const val LOCK_TAG_CATEGORIES_SHARE_ROW_EXCLUSIVE = "LOCK TABLE tag_categories IN SHARE ROW EXCLUSIVE MODE"

/** How long a registry write waits for that lock: the room lock's default wait; not configurable (ADMIN writes). */
const val TAG_REGISTRY_LOCK_WAIT_MILLIS = 3000

/**
 * The ONE order of tag names, in the database and in Kotlin: by code point (`COLLATE "C"`, UTF-8 byte order). The database's own collation
 * would order `a-c` and `ab` by the server's libc and Kotlin's `sorted()` by UTF-16 unit, so a POST's answer and a later read could
 * disagree.
 * Every list of tag names that is ever shown sorts this way (a thread's tags too, 1.2 S7; `persistence.md` "Tag categories (V32)").
 */
val CODE_POINT_ORDER: Comparator<String> = Comparator { a, b ->
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val x = a.codePointAt(i)
        val y = b.codePointAt(j)
        if (x != y) return@Comparator x.compareTo(y)
        i += Character.charCount(x)
        j += Character.charCount(y)
    }
    (a.length - i).compareTo(b.length - j)
}

/**
 * The registry list's sortable columns. `name` is the registry's ONE order of category names: lower-cased by code point (`COLLATE "C"`),
 * the default of `GET /tag-categories`, so a page, its sort and the order of the whole registry never depend on the server's libc.
 */
private val SORTABLE_COLUMNS: Map<String, Expression<*>> = mapOf(
    "id" to TagCategoryService.TagCategories.id,
    "name" to TagCategoryService.TagCategories.name.lowerCase().collateC(),
)

/** The ONE sortable whitelist: derived from the column map, never typed twice (`conventions.md`). */
val TAG_CATEGORY_SORT_FIELDS: Set<String> = SORTABLE_COLUMNS.keys

/** The default sort of `GET /tag-categories`: the registry's name order (the `id` tiebreaker is appended by `parsePaging`). */
val TAG_CATEGORY_DEFAULT_SORT: List<SortField> = listOf(SortField("name", descending = false))

/**
 * The tag registry (`tag_categories` + `tags`, V32). Every mutation is a [lockingTransaction] (a table lock that also covers an empty
 * registry, Toadie's protocol, `infra/db/Locking.kt`), so two admins editing at once queue instead of both claiming the same tag. A tag is
 * a row: removing it from a category, or deleting the category, RETIRES it (`marked_as_deleted`) rather than deleting it, so the threads
 * that carry it keep a real row; putting the name in a category again revives that row. Reads are plain transactions.
 */
class TagCategoryService(private val database: R2dbcDatabase) {
    object TagCategories : UIntIdTable("tag_categories"), SoftDeletable {
        /** Unique among ACTIVE rows, case-insensitively, by `uq_tag_categories_name_active` (V32): not an Exposed index. */
        val name = varchar("name", length = MAX_CATEGORY_NAME_LENGTH)
        val description = varchar("description", length = MAX_CATEGORY_DESCRIPTION_LENGTH).nullable()
        val revision = long("revision").default(1L)
        val createdAt = long("created_at")
        val updatedAt = long("updated_at")
        override val markedAsDeleted = bool("marked_as_deleted").default(false)
    }

    object Tags : UIntIdTable("tags"), SoftDeletable {
        val categoryId = reference("category_id", TagCategories)

        /** Normalised ([normalizedTagName]) and unique over every row, retired ones included, by `uq_tags_name`. */
        val name = varchar("name", length = MAX_TAG_NAME_LENGTH)
        override val markedAsDeleted = bool("marked_as_deleted").default(false)
    }

    private val categories = TagCategories
    private val tags = Tags

    private suspend fun <T> writeTransaction(block: suspend R2dbcTransaction.() -> T): T =
        lockingTransaction(database, TAG_REGISTRY_LOCK_WAIT_MILLIS, LOCK_TAG_CATEGORIES_SHARE_ROW_EXCLUSIVE, block = block)

    /**
     * One page of the active categories ([paging]'s sort, `name` by default), each with its live tags in [CODE_POINT_ORDER]. The count and
     * the page rows share ONE transaction and one predicate (the registry holds at most [MAX_TAG_CATEGORIES], so the SPA reads it whole).
     */
    suspend fun list(paging: PageRequest): TagCategoryListResult = suspendTransaction(database) {
        val total = categories.selectAll().where { categories.active() }.count()
        val rows = categories.selectAll().where { categories.active() }.applyPaging(paging, SORTABLE_COLUMNS).toList()
        val tagsByCategory = tags.selectAll().where { tags.active() and (tags.categoryId inList rows.map { it[categories.id] }) }
            .orderBy(tags.name.collateC() to SortOrder.ASC).toList()
            .groupBy({ it[tags.categoryId].value }, { it[tags.name] })
        TagCategoryListResult(
            items = rows.map {
                val id = it[categories.id].value
                TagCategory(id, it[categories.name], it[categories.description], it[categories.revision], tagsByCategory[id].orEmpty())
            },
            total = total,
        )
    }

    /**
     * Claims [names] for the category: a name no row holds is inserted, a RETIRED one (or a live one of this category) is revived into
     * it, a LIVE one of another category is a 409 naming the owner; every live tag of the category missing from [names] is retired.
     */
    private suspend fun applyTags(categoryId: UInt, names: List<String>) {
        val held = tags.selectAll().where { tags.name inList names }.toList().associateBy { it[tags.name] }
        for (name in names) {
            val row = held[name]
            when {
                row == null -> tags.insert {
                    it[tags.categoryId] = categoryId
                    it[tags.name] = name
                }
                !row[tags.markedAsDeleted] && row[tags.categoryId].value != categoryId -> {
                    val owner = categories.selectAll().where { categories.id eq row[tags.categoryId] }.toList().single()[categories.name]
                    throw ConflictException("Tag '$name' already belongs to category '$owner'")
                }
                row[tags.markedAsDeleted] || row[tags.categoryId].value != categoryId -> tags.update({ tags.id eq row[tags.id] }) {
                    it[tags.categoryId] = categoryId
                    it[tags.markedAsDeleted] = false
                }
            }
        }
        val dropped = (tags.categoryId eq categoryId) and tags.active() and (tags.name notInList names)
        tags.update({ dropped }) { it[tags.markedAsDeleted] = true }
    }

    /** Creates the category with its tags in ONE locked transaction; a name clash with an active category is the central 23505 to 409. */
    suspend fun create(request: TagCategoryRequest): TagCategory {
        validateTagCategoryRequest(request) // re-checked service-side so a direct caller stays guarded
        return writeTransaction {
            if (categories.selectAll().where { categories.active() }.count() >= MAX_TAG_CATEGORIES) {
                throw BadRequestException("The tag registry is full ($MAX_TAG_CATEGORIES categories)")
            }
            val stamp = nowMillis()
            val id = categories.insert {
                it[name] = request.name
                it[description] = request.description
                it[createdAt] = stamp
                it[updatedAt] = stamp
            }[categories.id].value
            applyTags(id, request.tags)
            TagCategory(id, request.name, request.description, 1L, request.tags.sortedWith(CODE_POINT_ORDER))
        }
    }

    /** The active category [id]'s stored revision, or null when it is missing or deleted; runs inside the registry lock. */
    private suspend fun revisionOf(id: UInt): Long? =
        categories.selectAll().where { (categories.id eq id) and categories.active() }.toList().singleOrNull()?.get(categories.revision)

    /**
     * A full replace of the category, rename and tag list included, under `X-Expected-Revision`. Existence comes first (a missing category
     * is `0`, the route's 404, even when the payload claims a tag another category holds), then the stale revision (`409`, judged under
     * the registry lock, so an admin who loaded the category before another admin saved it conflicts instead of overwriting the tag
     * list), then the claims. Every PUT bumps the revision. A tag dropped from the list is retired, not erased; moving a tag between
     * categories is two saves (drop it from the first, then add it to the second, which revives it with its threads).
     */
    suspend fun update(id: UInt, expectedRevision: Long, request: TagCategoryRequest): Int {
        validateTagCategoryRequest(request)
        return writeTransaction {
            val current = revisionOf(id) ?: return@writeTransaction 0
            requireRevision(expectedRevision, current)
            categories.update({ (categories.id eq id) and categories.active() }) {
                it[name] = request.name
                it[description] = request.description
                it[revision] = current + 1
                it[updatedAt] = nowMillis()
            }
            applyTags(id, request.tags)
            1
        }
    }

    /**
     * Soft delete under `X-Expected-Revision`: the category frees its name and its tags are retired (threads keep showing them as retired).
     * A stale revision is `409` (the admin confirmed deleting a tag list that has changed since). `0` = missing (404).
     */
    suspend fun delete(id: UInt, expectedRevision: Long): Int = writeTransaction {
        val current = revisionOf(id) ?: return@writeTransaction 0
        requireRevision(expectedRevision, current)
        categories.update({ (categories.id eq id) and categories.active() }) {
            it[markedAsDeleted] = true
            it[revision] = current + 1
            it[updatedAt] = nowMillis()
        }
        tags.update({ (tags.categoryId eq id) and tags.active() }) { it[tags.markedAsDeleted] = true }
        1
    }
}
