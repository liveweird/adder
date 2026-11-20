package ch.nokillswit

import ch.nokillswit.infra.mail.LogMailer
import ch.nokillswit.infra.mail.SmtpMailer
import ch.nokillswit.infra.mail.mailer
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import jakarta.mail.MessagingException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The outbound-email transport selection (infra/mail/Mail.kt, ported from Lettuce): the
 * `log`/`smtp`/`disabled` matrix, the production refusal of the log transport (the
 * fail-closed family), and the blank-SMTP-host refusal in any mode. No consumer endpoint
 * exists yet — password reset / MFA arrive with their own ports — so delivery is exercised
 * at the Mailer level.
 */
class MailTransportTest {

    private fun io.ktor.server.testing.ApplicationTestBuilder.captureApplication(): () -> Application {
        var app: Application? = null
        application { app = this }
        return { assertNotNull(app, "application must have started") }
    }

    @Test
    fun `production mode refuses to start with the log transport`() = testApplication {
        // Strong JWT secret so the earlier configureSecurity check passes; configureMail runs
        // at the top of the infrastructure group, before Flyway/Bootstrap.
        configureApp("jwt.secret" to strongJwtSecret(), "security.encryption.key" to strongEncryptionKey())
        serverConfig { developmentMode = false }
        assertStartupFails("mail.transport") { startApplication() }
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.productionSmtp(startTls: Boolean, ssl: Boolean) {
        // No encryption key: the crypto check that follows the mail check is what fails when the mail check passes.
        configureApp(
            "jwt.secret" to strongJwtSecret(),
            "mail.transport" to "smtp",
            "mail.smtp.host" to "smtp.adder.test",
            "mail.smtp.startTls" to startTls.toString(),
            "mail.smtp.ssl" to ssl.toString(),
        )
        serverConfig { developmentMode = false }
    }

    @Test
    fun `production mode refuses a plaintext smtp session`() = testApplication {
        productionSmtp(startTls = false, ssl = false)
        assertStartupFails("SMTP_STARTTLS=false and SMTP_SSL=false") { startApplication() }
    }

    @Test
    fun `production mode accepts smtp over STARTTLS`() = testApplication {
        // Passes the mail check and stops at the next fail-closed check (the missing encryption key).
        productionSmtp(startTls = true, ssl = false)
        assertStartupFails("DATA_ENCRYPTION_KEY") { startApplication() }
    }

    @Test
    fun `production mode accepts implicit TLS without STARTTLS`() = testApplication {
        productionSmtp(startTls = false, ssl = true)
        assertStartupFails("DATA_ENCRYPTION_KEY") { startApplication() }
    }

    @Test
    fun `development mode still permits a plaintext smtp session (the compose Mailpit)`() = testApplication {
        configureApp(
            "mail.transport" to "smtp",
            "mail.smtp.host" to "localhost",
            "mail.smtp.startTls" to "false",
            "mail.smtp.ssl" to "false",
        )
        val app = captureApplication()
        startApplication()
        assertIs<SmtpMailer>(app().mailer())
    }

    @Test
    fun `smtp transport without a host refuses to start in any mode`() = testApplication {
        configureApp("mail.transport" to "smtp", "mail.smtp.host" to "")
        assertStartupFails("SMTP_HOST") { startApplication() }
    }

    @Test
    fun `an unknown transport refuses to start`() = testApplication {
        configureApp("mail.transport" to "carrier-pigeon")
        assertStartupFails("Unknown mail.transport") { startApplication() }
    }

    @Test
    fun `disabled transport publishes a null mailer`() = testApplication {
        configureApp("mail.transport" to "disabled")
        val app = captureApplication()
        startApplication()
        assertNull(app().mailer(), "disabled must publish no mailer")
    }

    @Test
    fun `the dev-default log transport delivers to the mail logger`() = testApplication {
        configureApp() // mail.transport defaults to `log`; tests run in development mode
        val app = captureApplication()
        startApplication()
        val mailer = assertIs<LogMailer>(app().mailer())
        val capture = LogCapture("ch.nokillswit.mail")
        try {
            mailer.send("someone@adder.test", "Test subject", "Body line one")
            val message = capture.events.firstOrNull {
                "To: someone@adder.test" in it.formattedMessage
            }?.formattedMessage
            assertNotNull(message, "the log transport should have logged the message")
            assertTrue("Subject: Test subject" in message)
            assertTrue("Body line one" in message)
        } finally {
            capture.detach()
        }
    }

    @Test
    fun `smtp transport publishes an SmtpMailer`() = testApplication {
        configureApp(
            "mail.transport" to "smtp",
            "mail.smtp.host" to "localhost",
            "mail.smtp.port" to "1",
            "mail.smtp.startTls" to "false",
        )
        val app = captureApplication()
        startApplication()
        assertIs<SmtpMailer>(app().mailer())
    }

    private fun session(startTls: Boolean, implicitTls: Boolean = false) =
        SmtpMailer("smtp.adder.test", 587, "", "", "adder@localhost", startTls, implicitTls).session.properties

    @Test
    fun `the session checks the server certificate's name whenever TLS is on and never writes false`() {
        val starttls = session(startTls = true)
        assertEquals("true", starttls.getProperty("mail.smtp.ssl.checkserveridentity"), "STARTTLS")
        assertEquals("true", starttls.getProperty("mail.smtp.starttls.required"))
        val implicit = session(startTls = false, implicitTls = true)
        assertEquals("true", implicit.getProperty("mail.smtp.ssl.checkserveridentity"), "implicit TLS")
        assertEquals("true", implicit.getProperty("mail.smtp.ssl.enable"))
        val both = session(startTls = true, implicitTls = true)
        assertEquals("true", both.getProperty("mail.smtp.ssl.checkserveridentity"))
        assertEquals("false", both.getProperty("mail.smtp.starttls.enable"), "implicit TLS wins over the upgrade")
        // No TLS: nothing to check, and the property is left to the library's default rather than written false.
        assertNull(session(startTls = false).getProperty("mail.smtp.ssl.checkserveridentity"))
    }

    @Test
    fun `a boot with smtp over STARTTLS publishes a mailer whose session checks the certificate name`() = testApplication {
        configureApp("mail.transport" to "smtp", "mail.smtp.host" to "localhost", "mail.smtp.startTls" to "true")
        val app = captureApplication()
        startApplication()
        val props = assertIs<SmtpMailer>(app().mailer()).session.properties
        assertEquals("true", props.getProperty("mail.smtp.ssl.checkserveridentity"))
    }

    @Test
    fun `smtp delivery failure surfaces as a MessagingException`() = testApplication {
        // Port 1 is closed: the send path (message building + transport) runs and fails fast —
        // consumers own their handling (the password-reset port audits and keeps the old hash).
        val mailer = SmtpMailer(
            host = "127.0.0.1",
            port = 1,
            user = "",
            password = "",
            from = "adder@localhost",
            startTls = false,
        )
        assertFailsWith<MessagingException> {
            mailer.send("nobody@adder.test", "Will not arrive", "Body")
        }
    }
}
