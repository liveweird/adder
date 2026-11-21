package ch.nokillswit.tags

import ch.nokillswit.infra.validation.requireNameAndDescription
import ch.nokillswit.infra.validation.requireNoDuplicates
import ch.nokillswit.infra.validation.sanitizeSingleLine
import ch.nokillswit.infra.validation.sanitizedDescription
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable

/**
 * The ADMIN-curated tag categories' wire shapes (V32, `.claude/docs/product.md` 1.2 D2, `persistence.md` "Tag categories (V32)";
 * Hammurabi's `tags/TagCategory.kt`, ported as it is, itself Toadie's without the Backstage entity kinds): a category is a display name,
 * an optional description and its tags, and each tag belongs to exactly ONE category (a cross-category clash is the service's 409). The
 * registry is the whitelist a thread's tags are checked against, strictly (1.2.0 S7).
 */
@Serializable
data class TagCategory(
    val id: UInt,
    val name: String,
    val description: String?,
    /** The concurrency token: send it back as `X-Expected-Revision` on the category's PUT or DELETE (starts at 1, every PUT bumps it). */
    val revision: Long,
    /** The category's live tags (normalised names), alphabetical. */
    val tags: List<String>,
)

/** The whole registry; plain `{items}` because it is a bounded, admin-curated set (`list-endpoints.md`). */
@Serializable
data class TagCategoryList(val items: List<TagCategory>)

/** Create and replace share the shape: a PUT is a full replace of the category, its tag list included. */
@Serializable
data class TagCategoryRequest(
    val name: String,
    val description: String? = null,
    val tags: List<String>,
)

const val MAX_TAG_CATEGORIES = 100
const val MAX_CATEGORY_TAGS = 100
const val MAX_CATEGORY_NAME_LENGTH = 60
const val MAX_CATEGORY_DESCRIPTION_LENGTH = 500
const val MAX_TAG_NAME_LENGTH = 40

private val WHITESPACE_RUN = Regex("\\s+")

/** A tag starts with a letter or digit and goes on with those, spaces and `. _ / + # : -` (no comma: event params join names with one). */
private val TAG_GRAMMAR = Regex("""[\p{L}\p{N}][\p{L}\p{N} ._/+#:-]*""")

/**
 * The four letters the Unicode standard marks Default_Ignorable_Code_Point that are ALSO \p{L} (so [TAG_GRAMMAR] would admit them): the
 * Hangul fillers U+115F, U+1160, U+3164 and U+FFA0, which render as nothing. A tag made of them (or ending in them) would be a second,
 * invisible spelling of a visible tag. Every other default-ignorable code point is a format character, a variation selector or a combining
 * mark (Cf, Mn, Me), none of them \p{L} or \p{N}, so the grammar already refuses it; this denylist is that set's remainder, exact and
 * closed (no `isWhitespace`/`isIdentifierIgnorable`: the first never matches them, the second only the Cf/Cc ones the grammar refuses).
 */
private val BLANK_RENDERING_LETTERS = setOf(0x115F, 0x1160, 0x3164, 0xFFA0)

/**
 * The ONE canonical form of a tag name: trimmed, control and hidden direction characters a 400 ([sanitizeSingleLine]), runs of whitespace
 * collapsed to one space, lower-cased. Applied at every entry point (the registry now, a thread's tag write in S7), so `Security` and
 * ` security ` are the tag `security`.
 */
fun normalizedTagName(raw: String): String = sanitizeSingleLine(raw, "Tag").replace(WHITESPACE_RUN, " ").lowercase()

/**
 * A normalised tag name's rule: 1-[MAX_TAG_NAME_LENGTH] characters of the [TAG_GRAMMAR]. The detail echoes the offending name for the
 * editor's sake but never an over-long one whole: past the limit it is cut to [MAX_TAG_NAME_LENGTH] code points and an ellipsis (a 400's
 * detail is logged and returned, and a request may carry up to the body ceiling in one name).
 */
fun requireValidTagName(name: String) {
    if (name.codePoints().anyMatch { it in BLANK_RENDERING_LETTERS }) {
        throw BadRequestException("A tag must not contain an invisible character")
    }
    if (name.isEmpty() || name.length > MAX_TAG_NAME_LENGTH || !TAG_GRAMMAR.matches(name)) {
        val shown = if (name.codePointCount(0, name.length) > MAX_TAG_NAME_LENGTH) {
            name.substring(0, name.offsetByCodePoints(0, MAX_TAG_NAME_LENGTH)) + "…"
        } else {
            name
        }
        throw BadRequestException(
            "Tag '$shown' must be 1-$MAX_TAG_NAME_LENGTH characters: letters, digits, and spaces . _ / + # : - inside",
        )
    }
}

/** Trims the payload (the name via the control-character-rejecting sanitiser) and normalises every tag name. */
fun sanitizedTagCategoryRequest(request: TagCategoryRequest): TagCategoryRequest = TagCategoryRequest(
    name = sanitizeSingleLine(request.name, "Name"),
    description = sanitizedDescription(request.description),
    tags = request.tags.map(::normalizedTagName),
)

/** The registry's validation rules - enforced by the route AND re-checked by the service. */
fun validateTagCategoryRequest(request: TagCategoryRequest) {
    requireNameAndDescription(request.name, request.description, MAX_CATEGORY_NAME_LENGTH, MAX_CATEGORY_DESCRIPTION_LENGTH)
    if (request.tags.isEmpty()) throw BadRequestException("A category needs at least one tag")
    if (request.tags.size > MAX_CATEGORY_TAGS) throw BadRequestException("A category holds at most $MAX_CATEGORY_TAGS tags")
    request.tags.forEach(::requireValidTagName)
    requireNoDuplicates(request.tags, "Tags must not repeat")
}
