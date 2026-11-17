package ch.nokillswit.infra.validation

import io.ktor.server.plugins.BadRequestException

/**
 * Canonical single-line identity fields (names and the like): trimmed, and control characters
 * are a clean 400 instead of stored garbage. Ported from Lettuce.
 */
fun sanitizeSingleLine(value: String, field: String): String {
    val trimmed = value.trim()
    if (trimmed.any { it.isISOControl() }) {
        throw BadRequestException("$field must not contain control characters")
    }
    return trimmed
}

/**
 * Rejects a collection carrying a duplicate, optionally folded before comparison (e.g. a case-insensitive check): the one
 * `size != toSet().size` check the registry validators share. [message] is the caller's exact rejection text. Ported from Toadie.
 */
fun requireNoDuplicates(values: Collection<String>, message: String, fold: (String) -> String = { it }) {
    val folded = values.map(fold)
    if (folded.size != folded.toSet().size) {
        throw BadRequestException(message)
    }
}

/** The optional description every registry carries: trimmed, control characters a 400, blank = absent. */
fun sanitizedDescription(raw: String?): String? = raw?.let { sanitizeSingleLine(it, "Description") }?.takeIf { it.isNotBlank() }

/** The name/description length rule every registry enforces — one wording, so the SPA's messages never drift per area. */
fun requireNameAndDescription(name: String, description: String?, maxName: Int, maxDescription: Int) {
    if (name.isBlank() || name.length > maxName) throw BadRequestException("Name must be 1-$maxName characters")
    if (description != null && description.length > maxDescription) {
        throw BadRequestException("Description must be at most $maxDescription characters")
    }
}

/**
 * The ONE canonical form of an email address (trimmed, lower-cased): user logins and every other email-keyed lookup.
 * Applied at EVERY entry point so one
 * mailbox is one account (`ADMIN@x` cannot create a second account beside `admin@x`, and a padded/case-variant login matches). A pure fold
 * that never throws: login must stay a uniform 401 on garbage.
 */
fun canonicalEmail(raw: String): String = raw.trim().lowercase()
