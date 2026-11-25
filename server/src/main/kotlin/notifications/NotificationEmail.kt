package ch.nokillswit.notifications

import ch.nokillswit.infra.mail.LocalizedText
import java.text.Normalizer
import java.util.Locale

/** Entries one digest lists; the rest are a count ("and 4 more"). */
internal const val DIGEST_MAX_ENTRIES = 10

/**
 * One line of a digest: WHO did it, in which ROOM, on which thread NUMBER. Nothing else about a notice may reach an email: never a thread
 * title and never any message text (`security.md` "Outbound email"), so the type has no field to carry one. [messageId] only builds
 * the link.
 */
internal data class DigestEntry(
    val type: NotificationType,
    val actorName: String,
    val roomId: UInt,
    val roomName: String,
    val threadNumber: Int?,
    val messageId: UInt?,
    /** Raw plain replies represented by this line; greater than one uses the actor-free grouped wording. */
    val replyCount: Int = 1,
)

/** A composed email: a subject and a plain-text body. */
internal data class DigestEmail(val subject: String, val body: String)

private val GREETING = LocalizedText(en = "Hi", pl = "Cześć")
private val INTRO = LocalizedText(en = "In Adder, while you were away:", pl = "W Adderze, gdy Cię nie było:")
private val MORE = LocalizedText(en = "...and %d more", pl = "...i jeszcze %d")
private val PREFERENCES = LocalizedText(en = "Change which emails you get:", pl = "Zmień, jakie wiadomości dostajesz:")
private val SUBJECT_PREFIX = LocalizedText(en = "Adder: ", pl = "Adder: ")
private val GROUPED_REPLY_FEW = LocalizedText(en = "%d new replies in #%d (%s)", pl = "%d nowe odpowiedzi w #%d (%s)")
private val GROUPED_REPLY_MANY = LocalizedText(en = "%d new replies in #%d (%s)", pl = "%d nowych odpowiedzi w #%d (%s)")

private fun groupedReplyPattern(count: Int): LocalizedText =
    if (count % 10 in 2..4 && count % 100 !in 12..14) GROUPED_REPLY_FEW else GROUPED_REPLY_MANY

/** The sentence of one entry (who, the thread's number, the room's name: never a title): the bell's own wording without the title. */
private fun entryText(type: NotificationType): LocalizedText = when (type) {
    NotificationType.MENTIONED -> LocalizedText(
        en = "%s mentioned you in #%d (%s)",
        pl = "%s wspomina o Tobie w #%d (%s)",
    )
    NotificationType.MESSAGE_QUOTED -> LocalizedText(
        en = "%s quoted your message in #%d (%s)",
        pl = "%s cytuje Twoją wiadomość w #%d (%s)",
    )
    NotificationType.TEAM_MENTIONED -> LocalizedText(
        en = "%s mentioned your team in #%d (%s)",
        pl = "%s wspomina o Twoim zespole w #%d (%s)",
    )
    NotificationType.WATCHERS_MENTIONED -> LocalizedText(
        en = "%s mentioned the watchers of #%d (%s)",
        pl = "%s wspomina o obserwujących wątek #%d (%s)",
    )
    NotificationType.EVERYONE_MENTIONED -> LocalizedText(
        en = "%s mentioned everyone in #%d (%s)",
        pl = "%s wspomina o wszystkich w #%d (%s)",
    )
    NotificationType.THREAD_REPLIED -> LocalizedText(
        en = "%s replied in #%d (%s)",
        pl = "%s odpowiada w #%d (%s)",
    )
    NotificationType.THREAD_CREATED -> LocalizedText(
        en = "%s started #%d (%s)",
        pl = "%s zakłada wątek #%d (%s)",
    )
    NotificationType.THREAD_ASSIGNED -> LocalizedText(
        en = "%s made you the owner of #%d (%s)",
        pl = "%s przekazuje Ci wątek #%d (%s)",
    )
    NotificationType.THREAD_OWNER_CHANGED -> LocalizedText(
        en = "%s changed the owner of #%d (%s)",
        pl = "%s zmienia właściciela wątku #%d (%s)",
    )
    NotificationType.THREAD_STATUS_CHANGED -> LocalizedText(
        en = "%s moved #%d (%s) to another status",
        pl = "%s zmienia status wątku #%d (%s)",
    )
    NotificationType.THREAD_CLOSED -> LocalizedText(
        en = "%s closed #%d (%s)",
        pl = "%s zamyka wątek #%d (%s)",
    )
    NotificationType.THREAD_REOPENED -> LocalizedText(
        en = "%s reopened #%d (%s)",
        pl = "%s ponownie otwiera wątek #%d (%s)",
    )
    NotificationType.ROOM_ADDED -> LocalizedText(
        en = "%s added you to %s",
        pl = "%s dodaje Cię do pokoju %s",
    )
}

/** `String.format` in the root locale: a digit is always an ASCII digit, whatever the server's default locale. */
private fun fmt(pattern: String, vararg args: Any): String = String.format(Locale.ROOT, pattern, *args)

private val COUNT_ONE = LocalizedText(en = "%d unseen notification", pl = "%d nieprzeczytane powiadomienie")
private val COUNT_FEW = LocalizedText(en = "%d unseen notifications", pl = "%d nieprzeczytane powiadomienia")
private val COUNT_MANY = LocalizedText(en = "%d unseen notifications", pl = "%d nieprzeczytanych powiadomień")

/**
 * The subject's count phrase, with the plural forms of each language: the plural class is chosen by the Polish rule (1, 2 to 4 except
 * 12 to 14, the rest) and a language that does not tell "few" from "many" writes the same text for both (English).
 */
private fun countPhrase(count: Int, language: String): String {
    val text = when {
        count == 1 -> COUNT_ONE
        count % 10 in 2..4 && count % 100 !in 12..14 -> COUNT_FEW
        else -> COUNT_MANY
    }
    return fmt(text.of(language), count)
}

/**
 * A name made safe for a plain-text body: control characters and line or paragraph separators become a space and bidirectional controls
 * go, so a name can never start a new line (a forged "entry" or a header-looking line) or reorder the text around it. Names are already
 * single-line (`sanitizeSingleLine`); this is the second line of defence, in the one place that writes them into mail.
 */
internal fun plainName(text: String): String = buildString {
    for (c in text) {
        when {
            c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069' || c == '\u200E' || c == '\u200F' || c == '\u061C' -> Unit
            Character.isISOControl(c) || c == '\u2028' || c == '\u2029' -> append(' ')
            else -> append(c)
        }
    }
}.trim()

/** U+2024 ONE DOT LEADER: reads as a full stop, is not one (no mail client links across it). */
private const val DOT_LOOKALIKE = '․'

/** U+2215 DIVISION SLASH: reads as a slash, is not one. */
private const val SLASH_LOOKALIKE = '∕'

/** U+2216 SET MINUS: reads as a backslash, is not one (Outlook links a UNC path, and `file:` followed by backslashes). */
private const val BACKSLASH_LOOKALIKE = '∖'

/** U+FF20 FULLWIDTH COMMERCIAL AT: reads as an at sign, is not an address separator. */
private const val AT_LOOKALIKE = '＠'

/** The full stops a domain name is written with: ASCII, the ideographic and the fullwidth ones (IDNA treats all of them as a dot). */
private fun isDot(c: Int): Boolean = c == '.'.code || c == 0x3002 || c == 0xFF0E || c == 0xFF61

/**
 * Text chosen by someone else (a room's name, a person's name) made safe for Adder's OWN mail, where a mail client turns a bare address
 * or domain into a link: the result can contain no address and no link. Deterministic:
 *
 * - every format character (Unicode category `Cf`: zero-width spaces and joiners, the soft hyphen, bidirectional controls) is DROPPED,
 *   then the text is NFC-normalised, then [plainName] runs. A soft hyphen or zero-width character beside a dot cannot hide it from the
 *   rule below (a linkifier and IDNA ignore them and still resolve the dot);
 * - `@` becomes U+FF20 (fullwidth at), so no `user@host` survives;
 * - the two slashes of `://` become U+2215 (division slash), so no scheme survives;
 * - EVERY backslash becomes U+2216 (set minus), so no UNC path and no backslash scheme survives;
 * - EVERY full stop (ASCII, ideographic U+3002, fullwidth U+FF0E, halfwidth ideographic U+FF61) that is not followed by a plain space or
 *   the end of the text becomes U+2024 (one dot leader), whatever stands on either side: the rule is "a dot that continues into anything
 *   is not a dot", never "a dot between letters" (a combining mark beside the dot defeated that), so no bare domain
 *   (`evil.example.com`, an IDN) survives. A full stop that ends a sentence is left alone; "J.K. Rowling" reads "J․K. Rowling" (a
 *   no-break space was made a plain space by [plainName], so "evil.&nbsp;com" reads "evil. com", which links to nothing).
 *
 * The text stays readable; what it can no longer do is be clicked.
 */
internal fun mailSafe(text: String): String {
    val plain = plainName(Normalizer.normalize(dropFormatCharacters(text), Normalizer.Form.NFC))
    val out = StringBuilder(plain.length)
    var i = 0
    while (i < plain.length) {
        val c = plain.codePointAt(i)
        val next = i + Character.charCount(c)
        if (c == '/'.code && plain.startsWith("//", i) && out.endsWith(":")) {
            out.append(SLASH_LOOKALIKE).append(SLASH_LOOKALIKE)
            i += 2
            continue
        }
        val continuesPastDot = isDot(c) && next < plain.length && plain[next] != ' '
        when {
            c == '@'.code -> out.append(AT_LOOKALIKE)
            c == '\\'.code -> out.append(BACKSLASH_LOOKALIKE)
            continuesPastDot -> out.append(DOT_LOOKALIKE)
            else -> out.appendCodePoint(c)
        }
        i = next
    }
    return out.toString()
}

/** [text] without its `Cf` (format) characters. */
private fun dropFormatCharacters(text: String): String = buildString(text.length) {
    text.codePoints().forEach { if (Character.getType(it) != Character.FORMAT.toInt()) appendCodePoint(it) }
}

/**
 * The digest of [entries] (at least one, oldest first) for [name], in [language] (English for anything unknown). Pure: names and
 * numbers in,
 * text out. The subject carries a count and no name. The body lists at most [DIGEST_MAX_ENTRIES] entries, then "and N more"; a link follows
 * each entry, and the preferences link ends the mail, only when [appUrl] (the configured `mail.appUrl` origin, never a request's
 * `Host`) is set. [unlisted] is the person's further due notices that were claimed in the same digest but are not given as entries (a
 * flooded backlog): they count in the subject and in "and N more".
 */
internal fun notificationEmail(
    name: String,
    entries: List<DigestEntry>,
    appUrl: String?,
    language: String,
    unlisted: Int = 0,
): DigestEmail {
    require(entries.isNotEmpty()) { "a digest lists at least one notice" }
    val body = buildString {
        appendLine("${GREETING.of(language)} ${mailSafe(name)},")
        appendLine()
        appendLine(INTRO.of(language))
        appendLine()
        for (entry in entries.take(DIGEST_MAX_ENTRIES)) {
            val actor = mailSafe(entry.actorName)
            val room = mailSafe(entry.roomName)
            val number = entry.threadNumber
            val text = if (entry.type == NotificationType.THREAD_REPLIED && entry.replyCount > 1) {
                fmt(groupedReplyPattern(entry.replyCount).of(language), entry.replyCount, checkNotNull(number), room)
            } else {
                val pattern = entryText(entry.type).of(language)
                if (number == null) fmt(pattern, actor, room) else fmt(pattern, actor, number, room)
            }
            appendLine("- $text")
            if (appUrl != null) {
                val thread = number?.let { "/threads/$it" }.orEmpty()
                val message = entry.messageId?.let { "?message=$it" }.orEmpty()
                appendLine("  $appUrl/rooms/${entry.roomId}$thread$message")
            }
        }
        val more = maxOf(entries.size - DIGEST_MAX_ENTRIES, 0) + unlisted
        if (more > 0) appendLine(fmt(MORE.of(language), more))
        if (appUrl != null) {
            appendLine()
            appendLine("${PREFERENCES.of(language)} $appUrl/notification-preferences")
        }
    }
    return DigestEmail(SUBJECT_PREFIX.of(language) + countPhrase(entries.size + unlisted, language), body)
}
