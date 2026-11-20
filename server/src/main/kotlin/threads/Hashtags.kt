package ch.nokillswit.threads

import ch.nokillswit.workflows.shown
import io.ktor.server.plugins.BadRequestException
import java.text.Normalizer

// The hashtag grammar and its parser (`chat-model.md` "Hashtags (V15)"): PURE (no database, no Ktor), because the same rule runs in the
// SPA (its own parser) and `server/src/test/resources/hashtag-cases.json` pins both sides. PostgreSQL cannot parse a tag
// (its regex has no `\p{L}` and its classes follow the database locale) and cannot skip code the way the SPA's Markdown tree does, so
// nothing about tags is parsed in SQL. The Markdown pre-pass is `MarkdownText.kt`'s; every pass is linear in the body (a hostile body of
// thousands of `#` or backticks cannot make it quadratic).

/** The most distinct tags of one message that are indexed; the rest are ignored, never refused (a pasted stylesheet must not bounce it). */
const val MAX_TAGS_PER_MESSAGE = 20

/** The longest tag, in code points. A run of this plus one is no tag at all. */
const val MAX_TAG_LENGTH = 50

/** The most distinct values of the thread list's `tag` filter, counted after case folding. */
const val MAX_TAG_FILTER_VALUES = 10

/** The longest RAW `tag` filter value, in code points, that is normalised at all: four times a stored key, a bound on the work. */
private const val MAX_RAW_FILTER_LENGTH = 4 * MAX_STORED_TAG_LENGTH

/** The most distinct RAW `tag` values a request may carry before folding collapses them (a bound on the work, not on the meaning). */
const val MAX_RAW_TAG_VALUES = 50

/**
 * The grammar version a message's tags were indexed under (`messages.tags_version`; 0 = not indexed yet). Bumping it makes the boot
 * reindex rebuild every message's tags, which a grammar change needs.
 */
const val TAGS_VERSION: Short = 1

private const val TAG_CHARACTERS = "\\p{L}\\p{N}_-"

/** `#word` (1 to 50 tag characters, not followed by another one); the left boundary is [glued], not a lookbehind (see there). */
private val HASHTAG = Regex("#([$TAG_CHARACTERS]{1,$MAX_TAG_LENGTH})(?![$TAG_CHARACTERS])")

/** The characters a hash must not follow: a letter, a digit, another `#`, an HTML entity (`&#39;`) or a path (`/#anchor`). */
private val BLOCKER = Regex("[\\p{L}\\p{N}#&/]")

private fun String.hasLetter(): Boolean = codePoints().anyMatch { Character.isLetter(it) }

/**
 * The distinct hashtags of [body] in the order they appear, as typed (NFC-normalised; two spellings that differ only by case are one,
 * the first spelling wins), at most [MAX_TAGS_PER_MESSAGE]. The stored key is the folded form: [foldedTag] here and `public.unaccent` in
 * SQL (`MessageTagService`). Text in code (fenced, inline), link destinations, `<autolinks>` and bare URLs is not searched, which
 * approximates "the text outside code and links" the SPA's Markdown tree shows; a Markdown construct this does not see (an indented
 * code block, raw HTML) can disagree with it, and the shared case table pins the common cases. The NFC pass is bounded by the caller: a
 * body is at most 8,000 UTF-16 units, where canonical reordering of the worst run of misordered combining marks takes about 10 to 20 ms.
 */
fun parseHashtags(body: String): List<String> {
    if (!body.contains('#')) return emptyList()
    val text = textOutsideCodeAndLinks(Normalizer.normalize(body, Normalizer.Form.NFC))
    val seen = HashSet<String>()
    val tags = ArrayList<String>()
    for (match in HASHTAG.findAll(text)) {
        if (glued(text, match.range.first)) continue
        val tag = match.groupValues[1]
        if (!tag.hasLetter()) continue // `#42` is a thread number, not a tag
        if (seen.add(foldedTag(tag))) tags += tag
        if (tags.size == MAX_TAGS_PER_MESSAGE) break
    }
    return tags
}

/** Whether [old] and [new] carry different tag sets once folded: the edit's test for rewriting the index (the same set writes nothing). */
fun tagKeysDiffer(old: String, new: String): Boolean = tagKeysDiffer(old, parseHashtags(new))

/** The same test with the new body already parsed ([newTags], from [parseHashtags]): the edit parses it once, before the room lock. */
fun tagKeysDiffer(old: String, newTags: List<String>): Boolean = foldedKeys(parseHashtags(old)) != foldedKeys(newTags)

private fun foldedKeys(tags: List<String>): Set<String> = tags.mapTo(LinkedHashSet(), ::foldedTag)

/**
 * Whether the `#` at [hash] follows a character that forbids a tag. Checked here, not as a regex lookbehind: Java's lookbehind reads one
 * UTF-16 unit, so it would miss a supplementary-plane letter (a surrogate pair) glued to the hash, and the SPA's `u`-flag lookbehind
 * would not.
 */
private fun glued(text: String, hash: Int): Boolean =
    hash > 0 && BLOCKER.matches(String(Character.toChars(Character.codePointBefore(text, hash))))

/** The Kotlin half of the folding (case; `public.unaccent` strips the accents in SQL, on write and on filter). */
fun foldedTag(tag: String): String = tag.lowercase()

/**
 * The value of one `tag=` filter, case-folded (NFC, then lower case; `public.unaccent` and the cut to the column follow in SQL, as on
 * write): the SPA sends back either what a person typed (a tag as written, 1 to [MAX_TAG_LENGTH] characters) or a STORED KEY from the
 * room's tag list. A key is shaped by folding, not by the grammar: `unaccent` turns `ʻ` into `'`, `ß` into `ss` and `½` into `" 1/2"` (a
 * leading space, which the fold trims), so a key can hold punctuation and be longer than a tag (up to [MAX_STORED_TAG_LENGTH] characters).
 * So the rule is the key's own shape: 1 to [MAX_STORED_TAG_LENGTH] code points, no control character, no `#`; anything else is a `400`.
 * A value that matches no stored key matches nothing. The offending value is echoed bounded (`shown`), as every rejected filter key is.
 */
fun requireTagFilter(raw: String): String {
    // The length is bounded BEFORE the normalisation (canonical reordering is quadratic in a run of misordered combining marks). NFC
    // shrinks a text by at most 4:1 (it composes at most four code points into one: the longest canonical decomposition has four), so a
    // value that normalises to the 150 a key may hold is at most 600 raw code points: no valid value is cut here.
    val bounded = raw.codePointCount(0, raw.length) <= MAX_RAW_FILTER_LENGTH
    val tag = if (bounded) Normalizer.normalize(raw, Normalizer.Form.NFC) else raw
    val valid = bounded && tag.codePointCount(0, tag.length) in 1..MAX_STORED_TAG_LENGTH &&
        tag.codePoints().noneMatch { Character.isISOControl(it) || it == '#'.code }
    if (!valid) {
        throw BadRequestException("Tag '${raw.shown()}' must be 1-$MAX_STORED_TAG_LENGTH characters without '#' or control characters")
    }
    return foldedTag(tag)
}
