package ch.nokillswit

import ch.nokillswit.notifications.DIGEST_MAX_ENTRIES
import ch.nokillswit.notifications.DigestEntry
import ch.nokillswit.notifications.NotificationType
import ch.nokillswit.notifications.mailSafe
import ch.nokillswit.notifications.notificationEmail
import ch.nokillswit.notifications.plainName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The digest's text (`notifications/NotificationEmail.kt`): pure, names and numbers in, both languages, no database. */
class NotificationEmailTest {
    private val appUrl = "https://adder.example.com"

    private fun entry(
        type: NotificationType = NotificationType.MENTIONED,
        actor: String = "Bob",
        room: String = "Payments outage",
        number: Int = 42,
        messageId: UInt? = 991u,
        roomId: UInt = 12u,
    ) = DigestEntry(type, actor, roomId, room, number, messageId)

    @Test
    fun `English names who, the room, the number and a link`() {
        val mail = notificationEmail(
            "Ada",
            listOf(entry(), entry(NotificationType.THREAD_ASSIGNED, "Carol", "Runbook", 12, null, 9u)),
            appUrl, "en",
        )
        assertEquals("Adder: 2 unseen notifications", mail.subject)
        val lines = mail.body.lines()
        assertEquals("Hi Ada,", lines.first())
        assertTrue("- Bob mentioned you in #42 (Payments outage)" in lines)
        assertTrue("  $appUrl/rooms/12/threads/42?message=991" in lines)
        assertTrue("- Carol made you the owner of #12 (Runbook)" in lines)
        assertTrue("  $appUrl/rooms/9/threads/12" in lines, "a hand-over has no message to jump to")
        assertTrue("Change which emails you get: $appUrl/notification-preferences" in lines)
    }

    @Test
    fun `Polish text and plural forms`() {
        val mail = notificationEmail("Ada", listOf(entry()), appUrl, "pl")
        assertEquals("Adder: 1 nieprzeczytane powiadomienie", mail.subject)
        assertTrue(mail.body.contains("Cześć Ada,"))
        assertTrue(mail.body.contains("- Bob wspomina o Tobie w #42 (Payments outage)"))
        fun subject(n: Int) = notificationEmail("Ada", List(n) { entry() }, null, "pl").subject
        assertEquals("Adder: 2 nieprzeczytane powiadomienia", subject(2))
        assertEquals("Adder: 5 nieprzeczytanych powiadomień", subject(5))
        assertEquals("Adder: 12 nieprzeczytanych powiadomień", subject(12))
        assertEquals("Adder: 22 nieprzeczytane powiadomienia", subject(22))
    }

    @Test
    fun `an unknown language reads English`() {
        assertEquals("Adder: 1 unseen notification", notificationEmail("Ada", listOf(entry()), appUrl, "xx").subject)
    }

    @Test
    fun `only who, room and number reach the text`() {
        // A DigestEntry has no field for a title or a body (the proof is the type); the whole text is who, the room, the number and links.
        val mail = notificationEmail("Ada", listOf(entry()), appUrl, "en")
        assertEquals(
            "Hi Ada,\n\nIn Adder, while you were away:\n\n- Bob mentioned you in #42 (Payments outage)\n" +
                "  $appUrl/rooms/12/threads/42?message=991\n\nChange which emails you get: $appUrl/notification-preferences\n",
            mail.body,
        )
        assertEquals("Adder: 1 unseen notification", mail.subject)
    }

    @Test
    fun `ten entries, then and N more`() {
        val mail = notificationEmail("Ada", List(DIGEST_MAX_ENTRIES + 4) { entry(number = it + 1) }, appUrl, "en")
        assertEquals(DIGEST_MAX_ENTRIES, mail.body.lines().count { it.startsWith("- ") })
        assertTrue(mail.body.lines().contains("...and 4 more"))
        assertEquals("Adder: 14 unseen notifications", mail.subject, "the subject counts them all")
        assertTrue(notificationEmail("Ada", List(DIGEST_MAX_ENTRIES + 4) { entry() }, appUrl, "pl").body.contains("...i jeszcze 4"))
    }

    @Test
    fun `no link without a public origin`() {
        val mail = notificationEmail("Ada", listOf(entry()), null, "en")
        assertFalse(mail.body.contains("http"))
        assertFalse(mail.body.contains("notification-preferences"))
    }

    @Test
    fun `a name cannot break a line or the subject`() {
        val evil = "Eve\r\nBcc: victim@example.com\u2028- Mallory made you the owner\u202E"
        val mail = notificationEmail(evil, listOf(entry(actor = evil, room = evil)), appUrl, "en")
        assertFalse(mail.subject.contains('\n') || mail.subject.contains('\r') || mail.subject.contains("Eve"))
        assertTrue(mail.body.lines().none { it.startsWith("Bcc:") || it.startsWith("- Mallory") }, mail.body)
        assertFalse(mail.body.any { it == '\r' || it == '\u2028' || it == '\u202E' })
        assertEquals("Eve  Bcc: victim@example.com - Mallory made you the owner", plainName(evil))
    }

    @Test
    fun `a link is built from the configured origin and ids only`() {
        val mail = notificationEmail("Ada", listOf(entry(room = "x/../evil", messageId = 7u, roomId = 3u)), appUrl, "en")
        assertTrue(mail.body.lines().contains("  $appUrl/rooms/3/threads/42?message=7"))
    }

    /** What a mail client could link in [text]: a scheme, an address, a `www.` host or a dot between two word characters, in any script. */
    private fun linkable(text: String): Boolean =
        text.contains("://") || text.contains('@') || text.contains("www.", ignoreCase = true) ||
            Regex("[\\p{L}\\p{N}][.\\u3002\\uFF0E\\uFF61][\\p{L}\\p{N}]").containsMatchIn(text)

    @Test
    fun `a name or a room cannot make an address or a link`() {
        val hostile = listOf(
            "evil.example.com/login",
            "https://x.y",
            "a@b.c",
            "www.x.y",
            "WWW.X.Y",
            "\u043f\u0440\u0438\u043c\u0435\u0440.\u0440\u0444", // an IDN: a Cyrillic name and TLD
            "b\u00fccher.de",
            "\u4f8b\u3048\u3002jp", // an ideographic full stop
            "x\uFF0Ey", // a fullwidth full stop
            "mailto:a@b.c",
        )
        for (text in hostile) {
            assertTrue(linkable(text), "the fixture $text is linkable to begin with")
            val safe = mailSafe(text)
            assertFalse(linkable(safe), "$text -> $safe")
            val mail = notificationEmail(text, listOf(entry(actor = text, room = text)), appUrl, "en")
            // Only the configured origin's own links may remain: every other line of the mail is free of anything linkable.
            val foreign = mail.body.lines().filterNot { it.startsWith("  $appUrl/") || it.startsWith("Change which emails") }
            assertTrue(foreign.none { linkable(it) }, "$text -> ${mail.body}")
        }
    }

    @Test
    fun `mailSafe is deterministic, keeps the text readable and leaves ordinary names alone`() {
        assertEquals("evil\u2024example\u2024com/login", mailSafe("evil.example.com/login"))
        assertEquals("https:\u2215\u2215x\u2024y", mailSafe("https://x.y"))
        assertEquals("a\uFF20b\u2024c", mailSafe("a@b.c"))
        assertEquals("Payments outage", mailSafe("Payments outage"))
        assertEquals("Ada Lovelace-Smith (ops).", mailSafe("Ada Lovelace-Smith (ops)."), "a final full stop and a hyphen stay")
        assertEquals("Z\u00f3\u0142w", mailSafe("Z\u00f3\u0142w"))
        assertNotEquals("a@b.c", mailSafe("a@b.c"))
        assertEquals(mailSafe("a@b.c"), mailSafe("a@b.c"))
        assertEquals("1 \u2024 2", mailSafe("1 \u2024 2"))
        assertEquals("v. 2", mailSafe("v. 2"), "a full stop beside a space is no domain")
        assertEquals("", mailSafe(" \u202E "))
    }

    @Test
    fun `the room name keeps its words in the mail line`() {
        val mail = notificationEmail("Ada", listOf(entry(room = "Outage on shop.example.com", actor = "bob@evil.io")), appUrl, "en")
        assertTrue("- bob\uFF20evil\u2024io mentioned you in #42 (Outage on shop\u2024example\u2024com)" in mail.body.lines(), mail.body)
    }
}
