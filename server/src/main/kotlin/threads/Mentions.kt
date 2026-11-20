package ch.nokillswit.threads

// The mention grammar and its parser (`chat-model.md` "Mentions (V17)"): PURE (no database, no Ktor), because the same rule runs in the
// SPA (its own parser, over the Markdown tree) and `server/src/test/resources/mention-cases.json` pins both sides. A body stores the
// token `<@id>` as sent; nothing rewrites it. The Markdown pre-pass is `MarkdownText.kt`'s and every pass is linear in the body.

/** The most distinct mentions of one message that count; the rest are ignored, never refused. */
const val MAX_MENTIONS_PER_MESSAGE = 20

/** `<@id>`: an id of one to ten digits without a leading zero (the int32 bound is checked on the value). */
private val MENTION = Regex("<@([1-9][0-9]{0,9})>")

/**
 * The distinct user ids [body] mentions in the order they first appear, at most [MAX_MENTIONS_PER_MESSAGE]. The token is read only
 * outside the regions [textOutsideCodeAndLinks] blanks (code, link destinations, `<autolinks>`, definitions, bare URLs); a token right
 * after a backslash is no mention; an id past int32 (or `0`, or with a leading zero, or of eleven digits) is no mention. Whether the id
 * belongs to a member is not this function's question: the write keeps the active members of the room only.
 */
fun parseMentions(body: String): List<UInt> {
    if (!body.contains("<@")) return emptyList()
    val text = textOutsideCodeAndLinks(body)
    val seen = LinkedHashSet<UInt>()
    for (match in MENTION.findAll(text)) {
        val start = match.range.first
        if (start > 0 && text[start - 1] == '\\') continue
        val id = match.groupValues[1].toLong()
        if (id > Int.MAX_VALUE) continue
        seen += id.toUInt()
        if (seen.size == MAX_MENTIONS_PER_MESSAGE) break
    }
    return seen.toList()
}
