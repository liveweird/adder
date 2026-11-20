package ch.nokillswit

import ch.nokillswit.notifications.DIGEST_MAX_ENTRIES
import ch.nokillswit.notifications.DigestEntry
import ch.nokillswit.notifications.NotificationType
import ch.nokillswit.notifications.notificationEmail
import ch.nokillswit.notifications.plainName
import kotlin.test.Test
import kotlin.test.assertEquals
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
        val evil = "Eve\r\nBcc: victim@example.com - Mallory made you the owner‮"
        val mail = notificationEmail(evil, listOf(entry(actor = evil, room = evil)), appUrl, "en")
        assertFalse(mail.subject.contains('\n') || mail.subject.contains('\r') || mail.subject.contains("Eve"))
        assertTrue(mail.body.lines().none { it.startsWith("Bcc:") || it.startsWith("- Mallory") }, mail.body)
        assertFalse(mail.body.any { it == '\r' || it == ' ' || it == '‮' })
        assertEquals("Eve  Bcc: victim@example.com - Mallory made you the owner", plainName(evil))
    }

    @Test
    fun `a link is built from the configured origin and ids only`() {
        val mail = notificationEmail("Ada", listOf(entry(room = "x/../evil", messageId = 7u, roomId = 3u)), appUrl, "en")
        assertTrue(mail.body.lines().contains("  $appUrl/rooms/3/threads/42?message=7"))
    }
}
