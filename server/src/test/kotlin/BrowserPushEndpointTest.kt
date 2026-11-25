package ch.nokillswit

import ch.nokillswit.notifications.MAX_PUSH_ENDPOINT_LENGTH
import ch.nokillswit.notifications.validatePushEndpoint
import io.ktor.server.plugins.BadRequestException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BrowserPushEndpointTest {
    @Test
    fun `canonicalizes HTTPS endpoints for every supported provider`() {
        val endpoints = listOf(
            "https://fcm.googleapis.com/fcm/send/device",
            "https://fcm.googleapis.com:443/fcm/send/device",
            "https://updates.push.services.mozilla.com/wpush/v2/device",
            "https://db5.notify.windows.com/w/?token=device",
            "https://web.push.apple.com/QP/device",
            "https://subdomain.fcm.googleapis.com/push/device",
        )
        for (endpoint in endpoints) {
            assertEquals(endpoint.replace(".com:443", ".com"), validatePushEndpoint("  $endpoint  "))
        }
    }

    @Test
    fun `rejects malformed unsafe and lookalike endpoints`() {
        val invalid = listOf(
            "",
            "   ",
            "x".repeat(MAX_PUSH_ENDPOINT_LENGTH + 1),
            "not a URI",
            "http://fcm.googleapis.com/push",
            "https://fcm.googleapis.com:8443/push",
            "https://user@fcm.googleapis.com/push",
            "https://fcm.googleapis.com/push#fragment",
            "https://example.net/push",
            "https://fcm.googleapis.com.example.net/push",
            "//fcm.googleapis.com/push",
        )
        for (endpoint in invalid) assertFailsWith<BadRequestException>(endpoint) { validatePushEndpoint(endpoint) }
    }
}
