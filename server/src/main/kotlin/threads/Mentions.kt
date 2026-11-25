package ch.nokillswit.threads

// The mention grammar and its parser (`chat-model.md` "Mentions (V17)", "Group mentions (V25, V30)"): PURE (no database, no Ktor),
// because the same rule runs in the SPA (its own parser, over the Markdown tree) and
// `server/src/test/resources/mention-cases.json` pins both sides. A body stores the tokens `<@id>`, `<@team:id>`, `<@watchers>` and
// `<@everyone>` as sent; nothing rewrites them. The Markdown pre-pass is `MarkdownText.kt`'s and every pass is linear in the body.

/** The most distinct PEOPLE (`<@id>`) of one message that count; the rest are ignored, never refused. */
const val MAX_MENTIONS_PER_MESSAGE = 20

/** The most distinct TEAMS (`<@team:id>`) of one message that count; the rest are ignored, never refused. */
const val MAX_TEAM_MENTIONS_PER_MESSAGE = 5

/**
 * The one grammar, three forms that all start with `<@` (`<!...>` is an HTML declaration and `<team:42>` an autolink to the
 * Markdown parsers; `<@...>` is literal text to both): `<@id>` (group 1), `<@team:id>` (group 2), `<@watchers>` or `<@everyone>` (group
 * 3). An id is one to ten digits without a leading zero (the int32 bound is checked on the value); lowercase and exact.
 */
private val MENTION = Regex("<@(?:([1-9][0-9]{0,9})|team:([1-9][0-9]{0,9})|(watchers|everyone|here))>")

/**
 * What one body addresses, in the order each thing first appears: the distinct [users] (at most [MAX_MENTIONS_PER_MESSAGE]), the
 * distinct [teams] (at most [MAX_TEAM_MENTIONS_PER_MESSAGE]) and the two keywords. Whether an id belongs to a member, a team to a live
 * team with a handle, is not the parser's question: the write resolves them.
 */
data class ParsedMentions(
    val users: List<UInt> = emptyList(),
    val teams: List<UInt> = emptyList(),
    val watchers: Boolean = false,
    val everyone: Boolean = false,
    val here: Boolean = false,
) {
    /** The body names nothing. */
    val isEmpty: Boolean get() = users.isEmpty() && teams.isEmpty() && !watchers && !everyone && !here

    companion object {
        val NONE = ParsedMentions()
    }
}

/**
 * Everything [body] addresses. A token is read only outside the regions [textOutsideCodeAndLinks] blanks (code, link destinations,
 * `<autolinks>`, definitions, bare URLs); a token right after a backslash is no mention; an id past int32 (or `0`, or with a leading
 * zero, or of eleven digits) is none. The first [MAX_MENTIONS_PER_MESSAGE] distinct people and the first [MAX_TEAM_MENTIONS_PER_MESSAGE]
 * distinct teams count, each keyword once; the scan stays linear (a full pass, no early exit: a later keyword still counts).
 */
fun parseMentions(body: String): ParsedMentions {
    if (!body.contains("<@")) return ParsedMentions.NONE
    val text = textOutsideCodeAndLinks(body)
    val users = LinkedHashSet<UInt>()
    val teams = LinkedHashSet<UInt>()
    var watchers = false
    var everyone = false
    var here = false
    for (match in MENTION.findAll(text)) {
        val start = match.range.first
        if (start > 0 && text[start - 1] == '\\') continue
        val (person, team, keyword) = match.destructured
        when {
            person.isNotEmpty() -> users.addInt32(person, MAX_MENTIONS_PER_MESSAGE)
            team.isNotEmpty() -> teams.addInt32(team, MAX_TEAM_MENTIONS_PER_MESSAGE)
            keyword == "watchers" -> watchers = true
            keyword == "everyone" -> everyone = true
            else -> here = true
        }
    }
    return ParsedMentions(users.toList(), teams.toList(), watchers, everyone, here)
}

/** Adds the id [digits] spell to this set while it holds fewer than [limit] (an id past int32 is no id). */
private fun MutableSet<UInt>.addInt32(digits: String, limit: Int) {
    val id = digits.toLong()
    if (id <= Int.MAX_VALUE && size < limit) add(id.toUInt())
}
