package ch.nokillswit.teams

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.authz.ConflictException
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.infra.db.containsNormalized
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import ch.nokillswit.users.UserRole
import ch.nokillswit.users.UserService
import io.ktor.server.plugins.BadRequestException
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import ch.nokillswit.infra.db.SoftDeletable
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.db.lockActiveForWrite
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

val TeamServiceKey = AttributeKey<TeamService>("TeamService")

private val SORTABLE_COLUMNS: Map<String, Column<*>> = mapOf(
    "id" to TeamService.Teams.id,
    "name" to TeamService.Teams.name,
    "createdAt" to TeamService.Teams.createdAt,
    "updatedAt" to TeamService.Teams.updatedAt,
)

/** The ONE sortable whitelist — derived from the column map so the two can never drift. */
val TEAM_SORT_FIELDS: Set<String> = SORTABLE_COLUMNS.keys

class TeamService(private val database: R2dbcDatabase) {
    object Teams : UIntIdTable("teams"), SoftDeletable {
        // Case-folded name uniqueness rides the partial unique index uq_teams_name_active
        // (V6, active rows only) — Exposed defs are query-only, so no `.uniqueIndex()` here.
        val name = varchar("name", length = MAX_TEAM_NAME_LENGTH)
        val description = varchar("description", length = MAX_TEAM_DESCRIPTION_LENGTH).nullable()

        /** V6: the optional manager. May name a soft-deleted user — readers resolve it through ACTIVE users. */
        // The one foreign key from `teams` itself to Users (the roster's FK lives on TeamMembers), so the implicit
        // Teams -> Users join is unambiguous.
        val managerUserId = reference("manager_user_id", UserService.Users).nullable()
        val createdAt = long("created_at")
        val updatedAt = long("updated_at")
        override val markedAsDeleted = bool("marked_as_deleted").default(false)
    }

    /** The roster join (V10, hard-delete). A soft-deleted user keeps an inert row; every read resolves members through ACTIVE users. */
    object TeamMembers : Table("team_members") {
        val teamId = reference("team_id", Teams)
        val userId = reference("user_id", UserService.Users)
        override val primaryKey = PrimaryKey(teamId, userId)
    }

    suspend fun list(filter: TeamListFilter, paging: PageRequest): TeamListResult = suspendTransaction(database) {
        val predicate = buildPredicate(filter) and Teams.active()
        val total = Teams.selectAll().where { predicate }.count()
        val rows = Teams.selectAll().where { predicate }.applyPaging(paging, SORTABLE_COLUMNS).toList()
        val managers = activeUserNames(rows.mapNotNull { it[Teams.managerUserId]?.value })
        val counts = activeMemberCounts(rows.map { it[Teams.id].value })
        TeamListResult(
            items = rows.map { row ->
                val id = row[Teams.id].value
                val managerId = row[Teams.managerUserId]?.value?.takeIf { it in managers }
                TeamListItem(
                    id = id,
                    name = row[Teams.name],
                    description = row[Teams.description],
                    managerUserId = managerId,
                    managerName = managerId?.let(managers::get),
                    memberCount = counts[id] ?: 0,
                    createdAt = row[Teams.createdAt],
                    updatedAt = row[Teams.updatedAt],
                )
            },
            total = total,
        )
    }

    /** The team, or null when missing/deleted. */
    suspend fun read(id: UInt): TeamResponse? = suspendTransaction(database) {
        val row = Teams.selectAll().where { (Teams.id eq id) and Teams.active() }.toList().singleOrNull()
            ?: return@suspendTransaction null
        val managerId = row[Teams.managerUserId]?.value
        val managerName = managerId?.let { activeUserNames(listOf(it))[it] }
        TeamResponse(
            id = id,
            name = row[Teams.name],
            description = row[Teams.description],
            managerUserId = managerId.takeIf { managerName != null },
            managerName = managerName,
            members = activeMembersOf(id),
            createdAt = row[Teams.createdAt],
            updatedAt = row[Teams.updatedAt],
        )
    }

    /**
     * Creates the team and its optional manager in ONE transaction: an unknown or soft-deleted manager id is a 400
     * (client-supplied FK — never a 500 from the constraint). A name clash with an active team rides the V6 partial
     * index into the central 23505 → 409.
     */
    suspend fun create(request: TeamCreateRequest): UInt = suspendTransaction(database) {
        validateTeamCreate(request) // re-checked service-side so direct callers stay guarded
        requireActiveManager(request.managerUserId)
        val stamp = nowMillis()
        Teams.insert {
            it[name] = request.name
            it[description] = request.description
            it[managerUserId] = request.managerUserId
            it[createdAt] = stamp
            it[updatedAt] = stamp
        }[Teams.id].value
    }

    /**
     * Name/description/manager replace (full replace — an omitted manager clears it). `affected` 0 → 404;
     * `managerFrom` is the stored manager before the write.
     */
    suspend fun update(id: UInt, request: TeamUpdateRequest): TeamUpdateOutcome = suspendTransaction(database) {
        validateTeamUpdate(request)
        // NO KEY UPDATE: serializes with other team writers but not with FK-checking inserts that reference the row (persistence.md
        // "Row locks vs. FK locks").
        val before = Teams.selectAll().where { (Teams.id eq id) and Teams.active() }
            .forUpdate(ForUpdateOption.PostgreSQL.ForNoKeyUpdate).toList().singleOrNull()
            ?: return@suspendTransaction TeamUpdateOutcome(0, null)
        requireActiveManager(request.managerUserId)
        val affected = Teams.update({ (Teams.id eq id) and Teams.active() }) {
            it[name] = request.name
            it[description] = request.description
            it[managerUserId] = request.managerUserId
            it[updatedAt] = nowMillis()
        }
        TeamUpdateOutcome(affected, before[Teams.managerUserId]?.value)
    }

    /**
     * Whether [userId] is the team's current manager: an ACTIVE team whose manager is this ACTIVE user — one
     * query. False for a missing/deleted team too, so `requireTeamManagerOrAdmin` answers a uniform 403.
     */
    suspend fun isActiveManager(teamId: UInt, userId: UInt): Boolean = suspendTransaction(database) {
        Teams.innerJoin(UserService.Users) // the one Teams → users FK is `manager_user_id`
            .select(Teams.id)
            .where {
                (Teams.id eq teamId) and Teams.active() and (Teams.managerUserId eq userId) and UserService.Users.active()
            }
            .count() > 0
    }

    /**
     * The caller as the database knows them NOW: the role from the `users` row, not the token's claim (an ADMIN demoted since the token
     * was minted is a plain user; a soft-deleted or missing account has no role at all). The roster guard runs it so the verdict it
     * re-runs under the team lock judges the stored role, the way `withRoomWrite` re-reads the caller (`rooms/RoomLock.kt`).
     */
    suspend fun currentCaller(caller: CallerPrincipal): CallerPrincipal = suspendTransaction(database) {
        val users = UserService.Users
        val role = users.select(users.role).where { (users.id eq caller.userId) and users.active() }
            .toList().singleOrNull()?.let { UserRole.valueOf(it[users.role]) }
        caller.copy(roles = setOfNotNull(role))
    }

    /**
     * PUT semantics: adds [userId] to the roster, idempotently. Returns true on an actual add, false when already a member (no
     * write, so the route audits only a change). Runs in ONE transaction that locks the active team row first
     * ([lockActiveForWrite], `FOR NO KEY UPDATE`: serializes with team writers — a manager change included — and with other roster
     * writes, so the cap check below is race-free) and THEN runs [guard], the same guard the route ran up front: only this verdict
     * authorizes the write ("re-check under the lock", `requireTeamManagerOrAdmin`). The route's guard judges the caller as
     * [currentCaller] re-reads them, so an ADMIN demoted or deleted while the write waited for the lock is refused too. A missing or
     * deleted team therefore answers the
     * guard's uniform 403 to a non-admin and 404 to an admin; an unknown or soft-deleted user is 404; a roster at
     * [MAX_TEAM_MEMBERS] ACTIVE members is 409.
     */
    suspend fun addMember(teamId: UInt, userId: UInt, guard: suspend () -> Unit): Boolean = suspendTransaction(database) {
        lockTeamAndGuard(teamId, guard)
        val user = UserService.Users.select(UserService.Users.id)
            .where { (UserService.Users.id eq userId) and UserService.Users.active() }
            .count() > 0
        if (!user) throw NotFoundException("User not found")
        val present = TeamMembers.selectAll()
            .where { (TeamMembers.teamId eq teamId) and (TeamMembers.userId eq userId) }
            .count() > 0
        if (present) return@suspendTransaction false
        if ((activeMemberCounts(listOf(teamId))[teamId] ?: 0) >= MAX_TEAM_MEMBERS) {
            throw ConflictException("A team may have at most $MAX_TEAM_MEMBERS members")
        }
        TeamMembers.insert {
            it[TeamMembers.teamId] = teamId
            it[TeamMembers.userId] = userId
        }
        Teams.update({ Teams.id eq teamId }) { it[updatedAt] = nowMillis() }
        true
    }

    /**
     * Removes one membership row under the same lock and guard as [addMember]; returns the row count (0 = not a member, the route's
     * 404). A missing team is 403 or 404 exactly as in [addMember].
     */
    suspend fun removeMember(teamId: UInt, userId: UInt, guard: suspend () -> Unit): Int = suspendTransaction(database) {
        lockTeamAndGuard(teamId, guard)
        val removed = TeamMembers.deleteWhere { (TeamMembers.teamId eq teamId) and (TeamMembers.userId eq userId) }
        if (removed > 0) Teams.update({ Teams.id eq teamId }) { it[updatedAt] = nowMillis() }
        removed
    }

    /** The write preamble: lock the active team row, run [guard] on the locked state, then 404 when there was no such team. */
    private suspend fun lockTeamAndGuard(teamId: UInt, guard: suspend () -> Unit) {
        val locked = Teams.lockActiveForWrite(teamId)
        guard() // a non-admin's verdict on a missing team is the uniform 403, so the order matters: guard BEFORE the 404
        if (!locked) throw NotFoundException("Team not found")
    }

    /**
     * The ids of the team's ACTIVE roster at this moment (at most [MAX_TEAM_MEMBERS]), or null when the team is missing or deleted: the
     * SNAPSHOT a room's team add copies (`rooms/RoomMemberService`, `chat-model.md` "Membership"). Joins through ACTIVE users, so a
     * soft-deleted roster member is skipped. Runs in the caller's transaction when there is one (the room write's), so the roster and the
     * membership rows it becomes commit together.
     */
    suspend fun activeRosterIds(teamId: UInt): List<UInt>? = suspendTransaction(database) {
        val exists = Teams.select(Teams.id).where { (Teams.id eq teamId) and Teams.active() }.count() > 0
        if (!exists) return@suspendTransaction null
        TeamMembers.innerJoin(UserService.Users)
            .select(UserService.Users.id)
            .where { (TeamMembers.teamId eq teamId) and UserService.Users.active() }
            .orderBy(UserService.Users.id to SortOrder.ASC)
            .limit(MAX_TEAM_MEMBERS)
            .map { it[UserService.Users.id].value }
            .toList()
    }

    /** The roster: ACTIVE users only (the inner join drops a soft-deleted member), by name then id, bounded by [MAX_TEAM_MEMBERS]. */
    private suspend fun activeMembersOf(teamId: UInt): List<TeamMemberResponse> =
        TeamMembers.innerJoin(UserService.Users)
            .select(UserService.Users.id, UserService.Users.name, UserService.Users.email)
            .where { (TeamMembers.teamId eq teamId) and UserService.Users.active() }
            .orderBy(UserService.Users.name to SortOrder.ASC, UserService.Users.id to SortOrder.ASC)
            .limit(MAX_TEAM_MEMBERS)
            .map { TeamMemberResponse(it[UserService.Users.id].value, it[UserService.Users.name], it[UserService.Users.email]) }
            .toList()

    /** One grouped query for a page's roster sizes (ACTIVE users only) — not a per-row lookup. */
    private suspend fun activeMemberCounts(teamIds: List<UInt>): Map<UInt, Int> {
        if (teamIds.isEmpty()) return emptyMap()
        val count = TeamMembers.userId.count()
        return TeamMembers.innerJoin(UserService.Users)
            .select(TeamMembers.teamId, count)
            .where { (TeamMembers.teamId inList teamIds) and UserService.Users.active() }
            .groupBy(TeamMembers.teamId)
            .map { it[TeamMembers.teamId].value to it[count].toInt() }
            .toList()
            .toMap()
    }

    /** Soft delete: the team stops reading as active and frees its name. `0` = missing or already deleted (404). */
    suspend fun delete(id: UInt): Int = suspendTransaction(database) {
        if (!Teams.lockActiveForWrite(id)) return@suspendTransaction 0
        Teams.update({ (Teams.id eq id) and Teams.active() }) {
            it[markedAsDeleted] = true
            it[updatedAt] = nowMillis()
        }
    }

    /** One batched lookup: the display names of the ACTIVE users among [ids] (a soft-deleted user is absent). */
    private suspend fun activeUserNames(ids: Collection<UInt>): Map<UInt, String> {
        if (ids.isEmpty()) return emptyMap()
        return UserService.Users.select(UserService.Users.id, UserService.Users.name)
            .where { (UserService.Users.id inList ids.distinct()) and UserService.Users.active() }
            .map { it[UserService.Users.id].value to it[UserService.Users.name] }
            .toList()
            .toMap()
    }

    /** The manager, when set, must be an ACTIVE user — a client-supplied FK is a 400, never a 500 from the constraint. */
    private suspend fun requireActiveManager(userId: UInt?) {
        if (userId == null) return
        val exists = UserService.Users.select(UserService.Users.id)
            .where { (UserService.Users.id eq userId) and UserService.Users.active() }
            .count() > 0
        if (!exists) throw BadRequestException("Unknown or deleted manager ids: $userId")
    }

    private fun buildPredicate(filter: TeamListFilter): Op<Boolean> {
        var op: Op<Boolean> = Op.TRUE
        filter.name?.takeIf { it.isNotBlank() }?.let { op = op and Teams.name.containsNormalized(it) }
        return op
    }
}
