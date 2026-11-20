package ch.nokillswit.notifications

import ch.nokillswit.infra.mail.LocalizedText
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
    val threadNumber: Int,
    val messageId: UInt?,
)

/** A composed email: a subject and a plain-text body. */
internal data class DigestEmail(val subject: String, val body: String)

private val GREETING = LocalizedText(en = "Hi", pl = "Cześć")
private val INTRO = LocalizedText(en = "In Adder, while you were away:", pl = "W Adderze, gdy Cię nie było:")
private val MORE = LocalizedText(en = "...and %d more", pl = "...i jeszcze %d")
private val PREFERENCES = LocalizedText(en = "Change which emails you get:", pl = "Zmień, jakie wiadomości dostajesz:")
private val SUBJECT_PREFIX = LocalizedText(en = "Adder: ", pl = "Adder: ")

private fun entryText(type: NotificationType): LocalizedText = when (type) {
    NotificationType.MENTIONED -> LocalizedText(
        en = "%s mentioned you in #%d (%s)",
        pl = "%s wspomina o Tobie w #%d (%s)",
    )
    NotificationType.THREAD_ASSIGNED -> LocalizedText(
        en = "%s made you the owner of #%d (%s)",
        pl = "%s przekazuje Ci wątek #%d (%s)",
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
            c in '‪'..'‮' || c in '⁦'..'⁩' || c == '‎' || c == '‏' || c == '؜' -> Unit
            Character.isISOControl(c) || c == ' ' || c == ' ' -> append(' ')
            else -> append(c)
        }
    }
}.trim()

/**
 * The digest of [entries] (at least one, oldest first) for [name], in [language] (English for anything unknown). Pure: names and
 * numbers in,
 * text out. The subject carries a count and no name. The body lists at most [DIGEST_MAX_ENTRIES] entries, then "and N more"; a link follows
 * each entry, and the preferences link ends the mail, only when [appUrl] (the configured `mail.appUrl` origin, never a request's
 * `Host`) is set.
 */
internal fun notificationEmail(name: String, entries: List<DigestEntry>, appUrl: String?, language: String): DigestEmail {
    require(entries.isNotEmpty()) { "a digest lists at least one notice" }
    val body = buildString {
        appendLine("${GREETING.of(language)} ${plainName(name)},")
        appendLine()
        appendLine(INTRO.of(language))
        appendLine()
        for (entry in entries.take(DIGEST_MAX_ENTRIES)) {
            val text = fmt(entryText(entry.type).of(language), plainName(entry.actorName), entry.threadNumber, plainName(entry.roomName))
            appendLine("- $text")
            if (appUrl != null) {
                val message = entry.messageId?.let { "?message=$it" }.orEmpty()
                appendLine("  $appUrl/rooms/${entry.roomId}/threads/${entry.threadNumber}$message")
            }
        }
        if (entries.size > DIGEST_MAX_ENTRIES) appendLine(fmt(MORE.of(language), entries.size - DIGEST_MAX_ENTRIES))
        if (appUrl != null) {
            appendLine()
            appendLine("${PREFERENCES.of(language)} $appUrl/notification-preferences")
        }
    }
    return DigestEmail(SUBJECT_PREFIX.of(language) + countPhrase(entries.size, language), body)
}
