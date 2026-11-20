package ch.nokillswit

import ch.nokillswit.notifications.DisabledNotificationPreference
import ch.nokillswit.notifications.MAX_DISABLED_PAIRS
import ch.nokillswit.notifications.NotificationChannel
import ch.nokillswit.notifications.NotificationType
import ch.nokillswit.notifications.validateNotificationPreferences
import io.ktor.server.plugins.BadRequestException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/** The notification-preferences PUT body's bound: the spec's `maxItems` and the validator agree, and the validator is real. */
class NotificationPreferenceBoundTest {

    private val everyPair = NotificationType.entries.flatMap { type ->
        NotificationChannel.entries.map { DisabledNotificationPreference(type, it) }
    }

    @Test
    fun `the spec's maxItems on the PUT body is the number of pairs the server enforces`() {
        val disabled = OpenApiSpec.parsed.components.schemas["NotificationPreferencesUpdate"]?.properties?.get("disabled")
        assertNotNull(disabled, "NotificationPreferencesUpdate.disabled is missing from the spec")
        assertEquals(MAX_DISABLED_PAIRS, disabled.maxItems)
        assertEquals(everyPair.size, MAX_DISABLED_PAIRS)
    }

    @Test
    fun `the validator answers the set, and refuses a repeated pair or a list longer than the pairs there are`() {
        assertEquals(everyPair.toSet(), validateNotificationPreferences(everyPair))
        assertEquals(emptySet(), validateNotificationPreferences(emptyList()))
        assertFailsWith<BadRequestException> { validateNotificationPreferences(everyPair + everyPair.first()) }
        assertFailsWith<BadRequestException> { validateNotificationPreferences(listOf(everyPair.first(), everyPair.first())) }
    }
}
