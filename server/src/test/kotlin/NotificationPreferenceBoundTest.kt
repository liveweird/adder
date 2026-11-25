package ch.nokillswit

import ch.nokillswit.notifications.MAX_PREFERENCE_PAIRS
import ch.nokillswit.notifications.NotificationChannel
import ch.nokillswit.notifications.NotificationPreferencePair
import ch.nokillswit.notifications.NotificationType
import ch.nokillswit.notifications.THREAD_NOTIFICATION_TYPES
import ch.nokillswit.notifications.validateNotificationPreferences
import io.ktor.server.plugins.BadRequestException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/** The notification-preferences PUT body's bound: the spec's `maxItems` and the validator agree, and the validator is real. */
class NotificationPreferenceBoundTest {

    private val everyPair = THREAD_NOTIFICATION_TYPES.flatMap { type ->
        NotificationChannel.entries.map { NotificationPreferencePair(type, it) }
    }

    @Test
    fun `the spec's maxItems on both lists of the PUT body is the number of pairs the server enforces`() {
        for (list in listOf("disabled", "enabled")) {
            val property = OpenApiSpec.parsed.components.schemas["NotificationPreferencesUpdate"]?.properties?.get(list)
            assertNotNull(property, "NotificationPreferencesUpdate.$list is missing from the spec")
            assertEquals(MAX_PREFERENCE_PAIRS, property.maxItems, list)
        }
        assertEquals(everyPair.size, MAX_PREFERENCE_PAIRS)
        assertEquals(24, MAX_PREFERENCE_PAIRS, "twelve kinds, two channels")
    }

    @Test
    fun `the spec's maxItems on the notification list's type filter is the number of kinds the server accepts`() {
        val parameter = OpenApiSpec.parsed.paths["/api/v1/notifications"]?.get?.parameters?.firstOrNull { it.name == "type" }
        assertNotNull(parameter, "the type parameter is missing from the spec")
        assertEquals(THREAD_NOTIFICATION_TYPES.size, parameter.schema.maxItems)
        assertEquals(12, THREAD_NOTIFICATION_TYPES.size)
        val feed = OpenApiSpec.parsed.paths["/api/v1/notifications/feed"]?.get?.parameters?.firstOrNull { it.name == "type" }
        assertEquals(NotificationType.entries.size, assertNotNull(feed).schema.maxItems)
        val roomPair = NotificationPreferencePair(NotificationType.ROOM_ADDED, NotificationChannel.IN_APP)
        assertFailsWith<BadRequestException> {
            validateNotificationPreferences(listOf(roomPair), emptyList())
        }
    }

    @Test
    fun `the validator answers both sets, and refuses a repeated pair, a pair in both lists or more pairs than there are`() {
        val all = validateNotificationPreferences(everyPair, emptyList())
        assertEquals(everyPair.toSet(), all.disabled)
        assertEquals(emptySet(), all.enabled)
        val split = validateNotificationPreferences(everyPair.take(3), everyPair.drop(3))
        assertEquals(everyPair.take(3).toSet(), split.disabled)
        assertEquals(everyPair.drop(3).toSet(), split.enabled)
        assertEquals(emptySet(), validateNotificationPreferences(emptyList(), emptyList()).disabled)

        assertFailsWith<BadRequestException> { validateNotificationPreferences(everyPair + everyPair.first(), emptyList()) }
        assertFailsWith<BadRequestException> { validateNotificationPreferences(listOf(everyPair.first(), everyPair.first()), emptyList()) }
        assertFailsWith<BadRequestException> { validateNotificationPreferences(emptyList(), listOf(everyPair.first(), everyPair.first())) }
        assertFailsWith<BadRequestException> { validateNotificationPreferences(listOf(everyPair.first()), listOf(everyPair.first())) }
        assertFailsWith<BadRequestException> { validateNotificationPreferences(everyPair, listOf(everyPair.last())) }
    }

    @Test
    fun `only a pair that differs from its default is a deviation`() {
        val inAppMention = NotificationPreferencePair(NotificationType.MENTIONED, NotificationChannel.IN_APP)
        val emailMention = NotificationPreferencePair(NotificationType.MENTIONED, NotificationChannel.EMAIL)
        // The defaults: in the app on, by email off. Disabling an email pair and enabling an in-app one say what the default says.
        val closedInApp = inAppMention.copy(type = NotificationType.THREAD_CLOSED)
        val choice = validateNotificationPreferences(listOf(inAppMention, emailMention), listOf(closedInApp))
        assertEquals(mapOf(inAppMention to false), choice.deviations())
        val optIn = validateNotificationPreferences(emptyList(), listOf(emailMention))
        assertEquals(mapOf(emailMention to true), optIn.deviations())
    }
}
