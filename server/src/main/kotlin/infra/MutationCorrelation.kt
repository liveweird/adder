package ch.nokillswit.infra

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException

/** Optional client correlation metadata for an API write and its live echo. It carries no idempotency guarantee. */
const val MUTATION_ID_HEADER = "X-Mutation-Id"

private val MUTATION_ID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

internal fun ApplicationCall.mutationId(): String? {
    val values = request.headers.getAll(MUTATION_ID_HEADER) ?: return null
    if (values.size != 1 || !MUTATION_ID_PATTERN.matches(values.single())) {
        throw BadRequestException("$MUTATION_ID_HEADER must be one canonical lowercase UUIDv4")
    }
    return values.single()
}
