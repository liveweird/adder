package ch.nokillswit.auth

import ch.nokillswit.audit.audit
import ch.nokillswit.infra.mail.LocalizedText
import ch.nokillswit.infra.mail.Mailer
import ch.nokillswit.users.UserService
import io.ktor.server.application.Application
import io.ktor.server.application.log
import kotlinx.coroutines.CancellationException

private val SECURITY_NOTICE_SUBJECT = LocalizedText(
    en = "Adder: security notice",
    pl = "Adder: powiadomienie o bezpieczeństwie",
)
private val PASSWORD_CHANGED_BY_OWNER = LocalizedText(
    en = "Your Adder password was changed and your existing sessions were signed out. " +
        "If you did not make this change, contact your administrator immediately.",
    pl = "Twoje hasło Adder zostało zmienione, a istniejące sesje zostały wylogowane. " +
        "Jeśli to nie Ty zmieniłeś/aś hasło, natychmiast skontaktuj się z administratorem.",
)
private val PASSWORD_CHANGED_BY_ADMIN = LocalizedText(
    en = "An administrator changed your Adder password and your existing sessions were signed out. " +
        "If you did not expect this change, contact your administrator immediately.",
    pl = "Administrator zmienił Twoje hasło Adder, a istniejące sesje zostały wylogowane. " +
        "Jeśli ta zmiana była nieoczekiwana, natychmiast skontaktuj się z administratorem.",
)

/**
 * Sends a mandatory account-security receipt directly, outside notification preferences. The caller invokes this only after the
 * credential transaction has committed. Delivery is best-effort: its failure is audited without the recipient or message contents
 * and can never undo the security change.
 */
internal suspend fun sendSecurityReceipt(
    app: Application,
    users: UserService,
    mailer: Mailer,
    userId: UInt,
    subject: LocalizedText,
    body: LocalizedText,
    failureEvent: String,
    failureLog: String,
) {
    try {
        val user = users.read(userId) ?: return
        mailer.send(user.email, subject.of(user.language), body.of(user.language))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // The transport exception can contain provider data or message contents: record its type only.
        audit(failureEvent, "userId" to userId.toLong(), "errorType" to e.javaClass.simpleName)
        app.log.error("{} ({})", failureLog, e.javaClass.simpleName)
    }
}

/** The locked-on security receipt for a successful self or administrator password change. */
internal suspend fun notifyPasswordChanged(
    app: Application,
    users: UserService,
    mailer: Mailer,
    userId: UInt,
    selfChange: Boolean,
) = sendSecurityReceipt(
    app = app,
    users = users,
    mailer = mailer,
    userId = userId,
    subject = SECURITY_NOTICE_SUBJECT,
    body = if (selfChange) PASSWORD_CHANGED_BY_OWNER else PASSWORD_CHANGED_BY_ADMIN,
    failureEvent = "password.change_notification_failed",
    failureLog = "Password change notification failed",
)
