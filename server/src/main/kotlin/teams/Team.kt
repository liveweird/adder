package ch.nokillswit.teams

import ch.nokillswit.infra.paging.PageResponse
import ch.nokillswit.infra.validation.sanitizeSingleLine
import ch.nokillswit.infra.validation.requireNameAndDescription
import ch.nokillswit.infra.validation.sanitizedDescription
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable

/**
 * Flat teams (V6): a name and a description — the ownership unit future features scope to — an optional manager (V6: at most
 * one user per team, assigned by an ADMIN; being a manager grants no write on the team RECORD) and a roster (V10). ADMIN
 * curates the record and the manager; ADMIN or the team's active manager edits the roster (`requireTeamManagerOrAdmin`);
 * everyone reads teams. No chain: Lettuce's management-chain rule is deliberately NOT ported (see
 * `.claude/docs/authorization.md`).
 */
const val MAX_TEAM_NAME_LENGTH = 100
const val MAX_TEAM_DESCRIPTION_LENGTH = 500

/** The mention handle's bounds (V25): a column of this width, and a handle of at least [MIN_TEAM_HANDLE_LENGTH] characters. */
const val MAX_TEAM_HANDLE_LENGTH = 30
const val MIN_TEAM_HANDLE_LENGTH = 2

/**
 * The handle grammar (`chat-model.md` "Group mentions"): lowercase ASCII words joined by single hyphens, starting with a letter.
 * Uppercase is rejected, never folded: what is stored is what is typed after `@`. The length bounds are checked beside it.
 */
private val TEAM_HANDLE_PATTERN = Regex("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")

/** Words a group mention already means, or may later: no team may take them (a `400`). */
private val RESERVED_TEAM_HANDLES = setOf("everyone", "watchers", "observers", "here", "all", "channel", "room", "team")

/** The roster cap (Covenant's constant): a team of hundreds is a registry smell, and the inline `members` list stays bounded. */
const val MAX_TEAM_MEMBERS = 200

@Serializable
data class TeamCreateRequest(
    val name: String,
    val description: String? = null,
    /** Optional manager — an ACTIVE user (400 otherwise); omitted = no manager. */
    val managerId: UInt? = null,
    /** Optional mention handle (V25): see [validateTeamHandle]; omitted = none (a blank one is a 400, not none). */
    val handle: String? = null,
)

@Serializable
data class TeamUpdateRequest(
    val name: String,
    val description: String? = null,
    /** PUT is a full replace: omitted = unset. An ACTIVE user (400 otherwise). */
    val managerId: UInt? = null,
    /** PUT is a full replace: omitted = the handle is cleared. */
    val handle: String? = null,
)

/** One roster row: an ACTIVE user's display fields (a soft-deleted member is absent, never marked). */
@Serializable
data class TeamMemberResponse(
    val userId: UInt,
    val name: String,
    val email: String,
)

@Serializable
data class TeamResponse(
    val id: UInt,
    val name: String,
    val description: String?,
    /** The mention handle (V25), null when the team has none. */
    val handle: String?,
    /** The manager as an ACTIVE user; null when unset OR when the assigned user was soft-deleted. */
    val managerId: UInt?,
    val managerName: String?,
    /** The roster: ACTIVE users only, at most [MAX_TEAM_MEMBERS], by name then id. */
    val members: List<TeamMemberResponse>,
    val createdAt: Long,
    val updatedAt: Long,
)

/** The list row. */
@Serializable
data class TeamListItem(
    val id: UInt,
    val name: String,
    val description: String?,
    val handle: String?,
    val managerId: UInt?,
    val managerName: String?,
    /** The roster size, ACTIVE users only. */
    val memberCount: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

typealias TeamPageResponse = PageResponse<TeamListItem>

data class TeamListFilter(
    val name: String? = null,
    /** Case- and accent-insensitive substring of the mention handle (the composer's team search). */
    val handle: String? = null,
)

/** `affected` is the updated-row count (0 → 404); `managerFrom` the STORED manager before the write (the audit delta). */
data class TeamUpdateOutcome(val affected: Int, val managerFrom: UInt?)

data class TeamListResult(val items: List<TeamListItem>, val total: Long)

/** Trims the scalars (the sanitizer convention — control characters are a 400); the handle is trimmed but never case-folded. */
fun sanitizedTeamCreate(request: TeamCreateRequest): TeamCreateRequest = TeamCreateRequest(
    name = sanitizeSingleLine(request.name, "Name"),
    description = sanitizedDescription(request.description),
    managerId = request.managerId,
    handle = sanitizedHandle(request.handle),
)

fun sanitizedTeamUpdate(request: TeamUpdateRequest): TeamUpdateRequest = TeamUpdateRequest(
    name = sanitizeSingleLine(request.name, "Name"),
    description = sanitizedDescription(request.description),
    managerId = request.managerId,
    handle = sanitizedHandle(request.handle),
)

private fun sanitizedHandle(raw: String?): String? = raw?.let { sanitizeSingleLine(it, "Handle") }

/** The team rules — enforced by the route AND re-checked by the service. */
fun validateTeam(name: String, description: String?, handle: String?) {
    requireNameAndDescription(name, description, MAX_TEAM_NAME_LENGTH, MAX_TEAM_DESCRIPTION_LENGTH)
    if (handle != null) validateTeamHandle(handle)
}

/** The handle rules (`chat-model.md` "Group mentions"): bounded, lowercase hyphenated words, not reserved. Uniqueness: the V25 index. */
private fun validateTeamHandle(handle: String) {
    if (handle.length !in MIN_TEAM_HANDLE_LENGTH..MAX_TEAM_HANDLE_LENGTH) {
        throw BadRequestException("Handle must be $MIN_TEAM_HANDLE_LENGTH-$MAX_TEAM_HANDLE_LENGTH characters")
    }
    if (!TEAM_HANDLE_PATTERN.matches(handle)) {
        throw BadRequestException("Handle must be lowercase letters, digits and single hyphens, starting with a letter")
    }
    if (handle in RESERVED_TEAM_HANDLES) throw BadRequestException("Handle \"$handle\" is reserved")
}

fun validateTeamCreate(request: TeamCreateRequest) = validateTeam(request.name, request.description, request.handle)

fun validateTeamUpdate(request: TeamUpdateRequest) = validateTeam(request.name, request.description, request.handle)

/**
 * A team a message can mention (`<@team:id>`): active and with a [handle] (`chat-model.md` "Group mentions"); [name] is its current
 * display name.
 */
class MentionableTeam(val id: UInt, val handle: String, val name: String)
