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
        number: Int? = if (type == NotificationType.ROOM_ADDED) null else 42,
        messageId: UInt? = if (number == null) null else 991u,
        roomId: UInt = 12u,
        replyCount: Int = 1,
    ) = DigestEntry(type, actor, roomId, room, number, messageId, replyCount)

    @Test
    fun `a grouped reply line counts replies without exposing an arbitrary actor`() {
        val english = notificationEmail(
            "Ada",
            listOf(entry(NotificationType.THREAD_REPLIED, actor = "Newest actor", replyCount = 12)),
            appUrl,
            "en",
        )
        assertEquals("Adder: 1 unseen notification", english.subject, "the subject counts displayed groups")
        assertTrue(english.body.contains("- 12 new replies in #42 (Payments outage)"))
        assertFalse(english.body.contains("Newest actor"))
        assertTrue(english.body.contains("?message=991"), "the link keeps the oldest included message target")

        val polish = notificationEmail(
            "Ada",
            listOf(entry(NotificationType.THREAD_REPLIED, replyCount = 3)),
            null,
            "pl",
        )
        assertTrue(polish.body.contains("- 3 nowe odpowiedzi w #42 (Payments outage)"))
    }

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
    fun `grouped replies use the Polish plural form for their reply count`() {
        val cases = mapOf(2 to "nowe odpowiedzi", 5 to "nowych odpowiedzi", 12 to "nowych odpowiedzi", 22 to "nowe odpowiedzi")
        for ((count, words) in cases) {
            val reply = entry(NotificationType.THREAD_REPLIED, room = "Room").copy(replyCount = count)
            val mail = notificationEmail("Ada", listOf(reply), appUrl, "pl")
            val lines = mail.body.lines()
            assertTrue("- $count $words w #42 (Room)" in lines)
            assertTrue("  $appUrl/rooms/12/threads/42?message=991" in lines)
        }
    }

    @Test
    fun `every kind has its own sentence in both languages and none carries a title`() {
        val english = mapOf(
            NotificationType.MENTIONED to "Bob mentioned you in #42 (Room)",
            NotificationType.MESSAGE_QUOTED to "Bob quoted your message in #42 (Room)",
            NotificationType.TEAM_MENTIONED to "Bob mentioned your team in #42 (Room)",
            NotificationType.WATCHERS_MENTIONED to "Bob mentioned the watchers of #42 (Room)",
            NotificationType.HERE_MENTIONED to "Bob mentioned people here in #42 (Room)",
            NotificationType.EVERYONE_MENTIONED to "Bob mentioned everyone in #42 (Room)",
            NotificationType.THREAD_REPLIED to "Bob replied in #42 (Room)",
            NotificationType.THREAD_CREATED to "Bob started #42 (Room)",
            NotificationType.THREAD_ASSIGNED to "Bob made you the owner of #42 (Room)",
            NotificationType.THREAD_OWNER_CHANGED to "Bob changed the owner of #42 (Room)",
            NotificationType.THREAD_STATUS_CHANGED to "Bob moved #42 (Room) to another status",
            NotificationType.THREAD_CLOSED to "Bob closed #42 (Room)",
            NotificationType.THREAD_REOPENED to "Bob reopened #42 (Room)",
            NotificationType.ROOM_ADDED to "Bob added you to Room",
        )
        val polish = mapOf(
            NotificationType.MENTIONED to "Bob wspomina o Tobie w #42 (Room)",
            NotificationType.MESSAGE_QUOTED to "Bob cytuje Twoją wiadomość w #42 (Room)",
            NotificationType.TEAM_MENTIONED to "Bob wspomina o Twoim zespole w #42 (Room)",
            NotificationType.WATCHERS_MENTIONED to "Bob wspomina o obserwujących wątek #42 (Room)",
            NotificationType.HERE_MENTIONED to "Bob wspomina o obecnych osobach w #42 (Room)",
            NotificationType.EVERYONE_MENTIONED to "Bob wspomina o wszystkich w #42 (Room)",
            NotificationType.THREAD_REPLIED to "Bob odpowiada w #42 (Room)",
            NotificationType.THREAD_CREATED to "Bob zakłada wątek #42 (Room)",
            NotificationType.THREAD_ASSIGNED to "Bob przekazuje Ci wątek #42 (Room)",
            NotificationType.THREAD_OWNER_CHANGED to "Bob zmienia właściciela wątku #42 (Room)",
            NotificationType.THREAD_STATUS_CHANGED to "Bob zmienia status wątku #42 (Room)",
            NotificationType.THREAD_CLOSED to "Bob zamyka wątek #42 (Room)",
            NotificationType.THREAD_REOPENED to "Bob ponownie otwiera wątek #42 (Room)",
            NotificationType.ROOM_ADDED to "Bob dodaje Cię do pokoju Room",
        )
        assertEquals(NotificationType.entries.toSet(), english.keys, "the table covers every kind")
        for (type in NotificationType.entries) {
            val en = notificationEmail("Ada", listOf(entry(type, room = "Room")), null, "en").body.lines()
            val pl = notificationEmail("Ada", listOf(entry(type, room = "Room")), null, "pl").body.lines()
            assertTrue("- ${english.getValue(type)}" in en, "$type in English: $en")
            assertTrue("- ${polish.getValue(type)}" in pl, "$type in Polish: $pl")
        }
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
    fun `a hostile name or room cannot make an address or a link in any kind or language`() {
        val hostile = listOf("evil.example.com/login", "https://x.y", "a@b.c", "www.x.y", "mailto:a@b.c")
        for (type in NotificationType.entries) {
            for (language in listOf("en", "pl")) {
                for (text in hostile) {
                    val mail = notificationEmail(text, listOf(entry(type, actor = text, room = text)), appUrl, language)
                    val own = { line: String ->
                        line.startsWith("  $appUrl/") || line.startsWith("Change which emails") || line.startsWith("Zmień, jakie")
                    }
                    assertTrue(mail.body.lines().filterNot(own).none { linkable(it) }, "$type/$language/$text -> ${mail.body}")
                }
            }
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
    fun `a backslash path or scheme cannot survive`() {
        assertEquals("\u2216\u2216fileshare\u2216x", mailSafe("\\\\fileshare\\x"))
        assertEquals("file:\u2216\u2216host\u2216x", mailSafe("file:\\\\host\\x"))
        for (text in listOf("\\\\fileshare\\x", "file:\\\\host\\x", "a\\b")) {
            val safe = mailSafe(text)
            assertFalse('\\' in safe, "$text -> $safe")
            val mail = notificationEmail(text, listOf(entry(actor = text, room = text)), appUrl, "en")
            assertFalse(mail.body.lines().filterNot { it.startsWith("  $appUrl/") }.any { '\\' in it }, "$text -> ${mail.body}")
        }
    }

    /** A blunt oracle, not the implementation's rule: no full stop of any kind continues into a non-space, and no `@`, no backslash. */
    private fun hasLiveDotOrAt(text: String): Boolean {
        if ('@' in text || '\\' in text) return true
        val dots = setOf('.', '。', '．', '｡')
        return text.windowed(2).any { it[0] in dots && it[1] != ' ' }
    }

    @Test
    fun `no real dot survives a combining mark, a format character or a no-break space beside it`() {
        val hostile = listOf(
            "evilé.com", // a combining acute before the dot
            "shop­.example­.com", // soft hyphens around the dots
            "evil.́com", // a combining mark after the dot
            "evil​.​example​.​com", // zero-width spaces around the dots
            "evil‍.com‌.uk", // zero-width joiner and non-joiner
            "evil. com", // a no-break space after a dot is no space
            "evil。́com",
            "evil．­com",
            "evil｡com",
            "a‮.‬b@c.d",
            "x.y...z",
            "\\\\fileshare\\x", // a UNC path
            "file:\\\\host\\x", // a backslash scheme
        )
        for (text in hostile) {
            val safe = mailSafe(text)
            assertFalse(hasLiveDotOrAt(safe), "$text -> $safe")
            assertTrue(safe.none { Character.getType(it) == Character.FORMAT.toInt() }, "no format character is left in $safe")
            val mail = notificationEmail(text, listOf(entry(actor = text, room = text)), appUrl, "en")
            val foreign = mail.body.lines().filterNot { it.startsWith("  $appUrl/") || it.startsWith("Change which emails") }
            assertFalse(foreign.any { hasLiveDotOrAt(it) }, "$text -> ${mail.body}")
        }
    }

    @Test
    fun `claimed notices that are not listed count in the subject and in and N more`() {
        val mail = notificationEmail("Ada", List(10) { entry(number = it + 1) }, appUrl, "en", unlisted = 110)
        assertEquals("Adder: 120 unseen notifications", mail.subject)
        assertTrue(mail.body.lines().contains("...and 110 more"), mail.body)
        val some = notificationEmail("Ada", List(12) { entry(number = it + 1) }, appUrl, "en", unlisted = 8)
        assertTrue(some.body.lines().contains("...and 10 more"), "the entries beyond ten and the unlisted add up")
    }

    @Test
    fun `a final full stop and a full stop before a space stay`() {
        assertEquals("J․K. Rowling", mailSafe("J.K. Rowling"))
        assertEquals("The end.", mailSafe("The end."))
        assertEquals("é․x", mailSafe("é.x"), "the text is NFC-normalised first")
        assertEquals("shop․example", mailSafe("shop­.example"), "a soft hyphen is dropped, the dot still read")
    }

    @Test
    fun `the room name keeps its words in the mail line`() {
        val mail = notificationEmail("Ada", listOf(entry(room = "Outage on shop.example.com", actor = "bob@evil.io")), appUrl, "en")
        assertTrue("- bob\uFF20evil\u2024io mentioned you in #42 (Outage on shop\u2024example\u2024com)" in mail.body.lines(), mail.body)
    }
}
