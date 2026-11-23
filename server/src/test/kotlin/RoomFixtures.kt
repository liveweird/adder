package ch.nokillswit

import ch.nokillswit.authz.RoomAccessType
import ch.nokillswit.authz.RoomRole
import ch.nokillswit.rooms.RoomCreateRequest
import ch.nokillswit.rooms.RoomService
import ch.nokillswit.rooms.RoomWorkflowService
import ch.nokillswit.rooms.WorkflowUsage
import ch.nokillswit.users.UserRole
import ch.nokillswit.workflows.FieldType
import ch.nokillswit.workflows.StatusCategory
import ch.nokillswit.workflows.StatusColour
import ch.nokillswit.workflows.WorkflowFieldDef
import ch.nokillswit.workflows.WorkflowOptionDef
import ch.nokillswit.workflows.WorkflowStatusDef
import ch.nokillswit.workflows.WorkflowTemplateListFilter
import ch.nokillswit.workflows.WorkflowTemplateRequest
import ch.nokillswit.workflows.WorkflowTemplateService
import io.ktor.client.HttpClient
import io.ktor.server.testing.ApplicationTestBuilder
import java.util.UUID
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import org.jetbrains.exposed.v1.core.and

/**
 * Room fixtures (0.2.0 C7): direct, route-free seeding of workflow templates and rooms past the HTTP guards, plus the usage-port
 * fake. A room's members are written here by raw insert (past the member routes' guards and the 500 cap). Every test mints UNIQUE names
 * and removes its own rows ([TestRooms.purge], [TestTemplates.delete]) so the shared container stays small.
 */
object TestTemplates {
    val service: WorkflowTemplateService by lazy { WorkflowTemplateService(sharedDatabaseForTests()) }

    private fun label(en: String, pl: String? = null): Map<String, String> =
        if (pl == null) mapOf("en" to en) else mapOf("en" to en, "pl" to pl)

    /**
     * A small bilingual template: `open` (initial, translated), `half` (EN only: the per-label EN fallback), `done` (CLOSED, translated),
     * a SELECT field `severity` (option `s1` translated, `s2` EN only) and a TEXT field `note` (EN only). [name] must be unique.
     */
    fun bilingual(name: String) = WorkflowTemplateRequest(
        name = name,
        description = "A fixture template",
        statuses = listOf(
            WorkflowStatusDef("open", label("Open", "Otwarte"), StatusColour.GRAY, StatusCategory.OPEN, initial = true),
            WorkflowStatusDef("half", label("Half only"), StatusColour.BLUE, StatusCategory.OPEN, initial = false),
            WorkflowStatusDef("done", label("Done", "Zrobione"), StatusColour.TEAL, StatusCategory.CLOSED, initial = false),
        ),
        fields = listOf(
            WorkflowFieldDef(
                key = "severity",
                label = label("Severity", "Ważność"),
                type = FieldType.SELECT,
                required = true,
                options = listOf(WorkflowOptionDef("s1", label("Sev one", "Waga jeden")), WorkflowOptionDef("s2", label("Sev two"))),
            ),
            WorkflowFieldDef("note", label("Note"), FieldType.TEXT, required = false, options = emptyList()),
        ),
    )

    suspend fun seed(prefix: String = "tpl", request: WorkflowTemplateRequest? = null): UInt =
        service.create(request ?: bilingual("$prefix-${UUID.randomUUID().toString().substring(0, 8)}"))

    /** The id of the active seeded template named [name] (V12: `Default`, `Incident`). */
    suspend fun seededId(name: String): UInt =
        service.list(WorkflowTemplateListFilter(name = name), ch.nokillswit.infra.paging.PageRequest(1, 100, emptyList()))
            .items.first { it.name == name }.id

    suspend fun delete(id: UInt) {
        service.delete(id)
    }
}

object TestRooms {
    val workflows: RoomWorkflowService by lazy { RoomWorkflowService(sharedDatabaseForTests()) }
    val service: RoomService by lazy { RoomService(sharedDatabaseForTests(), TestTemplates.service, workflows) }

    /** Creates a room as [ownerId] through the service (the real create path); fixtures default to restricted hidden. */
    suspend fun seed(
        ownerId: UInt,
        templateId: UInt,
        name: String,
        language: String? = null,
        accessType: RoomAccessType = RoomAccessType.RESTRICTED_HIDDEN,
    ): UInt = service.create(
        ownerId,
        RoomCreateRequest(name = name, templateId = templateId, language = language, accessType = accessType),
    )

    /** Adds [userId] to the room by raw insert, past the member routes' guards. */
    suspend fun addMember(roomId: UInt, userId: UInt, role: RoomRole = RoomRole.MEMBER) {
        suspendTransaction(sharedDatabaseForTests()) {
            RoomService.RoomMembers.insert {
                it[RoomService.RoomMembers.roomId] = roomId
                it[RoomService.RoomMembers.userId] = userId
                it[RoomService.RoomMembers.role] = role.name
                it[addedAt] = System.currentTimeMillis()
            }
        }
    }

    /**
     * Mints [count] fresh ACTIVE users (emails `<marker>-<n>@test`) and adds them to [roomId] as MEMBER, by raw SQL (a cap test needs
     * hundreds). Returns their ids so the test can soft-delete them afterwards (`TestUsers.softDelete`).
     */
    suspend fun seedMembers(roomId: UInt, count: Int, marker: String): List<UInt> = PostgresTestSupport.connect().use { connection ->
        val ids = connection.prepareStatement(
            "INSERT INTO users (\"name\", email, password_hash) " +
                "SELECT 'Seed ' || g, ? || '-' || g || '@test', 'x' FROM generate_series(1, ?) g RETURNING id",
        ).use { st ->
            st.setString(1, marker)
            st.setInt(2, count)
            st.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getLong(1).toUInt() else null }.toList() }
        }
        connection.prepareStatement(
            "INSERT INTO room_members (room_id, user_id, \"role\", added_at) SELECT ?, id, 'MEMBER', ? FROM users WHERE id = ANY (?)",
        ).use { st ->
            st.setInt(1, roomId.toInt())
            st.setLong(2, System.currentTimeMillis())
            st.setArray(3, connection.createArrayOf("bigint", ids.map { it.toLong() }.toTypedArray()))
            assertEquals(count, st.executeUpdate())
        }
        ids
    }

    /** Soft-deletes many users in one statement (the cleanup of [seedMembers]; `TestUsers.softDelete` is one transaction each). */
    fun softDeleteUsers(ids: Collection<UInt>) {
        if (ids.isEmpty()) return
        PostgresTestSupport.connect().use { connection ->
            connection.prepareStatement("UPDATE users SET marked_as_deleted = TRUE WHERE id = ANY (?)").use { st ->
                st.setArray(1, connection.createArrayOf("bigint", ids.map { it.toLong() }.toTypedArray()))
                st.executeUpdate()
            }
        }
    }

    /** The raw role of a membership row, or null when the user has none (an inert row of a deleted user included). */
    suspend fun rawRole(roomId: UInt, userId: UInt): RoomRole? = suspendTransaction(sharedDatabaseForTests()) {
        val members = RoomService.RoomMembers
        members.select(members.role).where { (members.roomId eq roomId) and (members.userId eq userId) }
            .map { RoomRole.valueOf(it[members.role]) }.toList().singleOrNull()
    }

    suspend fun setRole(roomId: UInt, userId: UInt, role: RoomRole) {
        suspendTransaction(sharedDatabaseForTests()) {
            val members = RoomService.RoomMembers
            members.update({ (members.roomId eq roomId) and (members.userId eq userId) }) { it[members.role] = role.name }
        }
    }

    data class RawRoom(val id: UInt, val name: String, val markedAsDeleted: Boolean, val archivedAt: Long?)

    suspend fun rawRooms(): List<RawRoom> = suspendTransaction(sharedDatabaseForTests()) {
        val r = RoomService.Rooms
        r.selectAll().map { RawRoom(it[r.id].value, it[r.name], it[r.markedAsDeleted], it[r.archivedAt]) }.toList()
    }

    suspend fun rawRoom(id: UInt): RawRoom? = rawRooms().firstOrNull { it.id == id }

    /** Archives a room by raw update (a fixture state for the unarchive rows). */
    suspend fun archive(roomId: UInt) {
        suspendTransaction(sharedDatabaseForTests()) {
            RoomService.Rooms.update({ RoomService.Rooms.id eq roomId }) { it[archivedAt] = System.currentTimeMillis() }
        }
    }

    /** The raw row counts of a room's `room_members`, `room_statuses` and `room_fields` (soft delete must leave them). */
    suspend fun rawChildCounts(roomId: UInt): Triple<Int, Int, Int> = PostgresTestSupport.connect().use { connection ->
        fun count(table: String): Int = connection.prepareStatement("SELECT count(*) FROM $table WHERE room_id = ?").use { st ->
            st.setInt(1, roomId.toInt())
            st.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
        Triple(count("room_members"), count("room_statuses"), count("room_fields"))
    }

    data class RawStatus(
        val key: String,
        val label: String,
        val category: String,
        val initial: Boolean,
        val position: Int,
        val retired: Boolean,
    )

    data class RawField(val key: String, val label: String, val type: String, val position: Int, val retired: Boolean)

    /** Every status row of a room, retired ones included, by key (the workflow GET shows the live ones only). */
    suspend fun rawStatuses(roomId: UInt): List<RawStatus> = suspendTransaction(sharedDatabaseForTests()) {
        val t = RoomWorkflowService.RoomStatuses
        t.selectAll().where { t.roomId eq roomId }.orderBy(t.key to org.jetbrains.exposed.v1.core.SortOrder.ASC).map {
            RawStatus(it[t.key], it[t.label], it[t.category], it[t.isInitial], it[t.position], it[t.markedAsDeleted])
        }.toList()
    }

    suspend fun rawFields(roomId: UInt): List<RawField> = suspendTransaction(sharedDatabaseForTests()) {
        val t = RoomWorkflowService.RoomFields
        t.selectAll().where { t.roomId eq roomId }.orderBy(t.key to org.jetbrains.exposed.v1.core.SortOrder.ASC).map {
            RawField(it[t.key], it[t.label], it[t.type], it[t.position], it[t.markedAsDeleted])
        }.toList()
    }

    /** Reserves [count] retired keys `r1..rN` in the room's statuses and fields by raw SQL (a key-budget test needs hundreds). */
    fun seedRetiredKeys(roomId: UInt, count: Int) {
        PostgresTestSupport.connect().use { connection ->
            connection.prepareStatement(
                "INSERT INTO room_statuses (room_id, \"key\", label, colour, category, is_initial, position, marked_as_deleted) " +
                    "SELECT ?, 'r' || g, 'Retired ' || g, 'GRAY', 'OPEN', FALSE, 0, TRUE FROM generate_series(1, ?) g",
            ).use { st ->
                st.setInt(1, roomId.toInt())
                st.setInt(2, count)
                assertEquals(count, st.executeUpdate())
            }
            connection.prepareStatement(
                "INSERT INTO room_fields (room_id, \"key\", label, \"type\", options, required, position, marked_as_deleted) " +
                    "SELECT ?, 'r' || g, 'Retired ' || g, 'TEXT', '[]'::jsonb, FALSE, 0, TRUE FROM generate_series(1, ?) g",
            ).use { st ->
                st.setInt(1, roomId.toInt())
                st.setInt(2, count)
                assertEquals(count, st.executeUpdate())
            }
        }
    }

    /** Removes the rooms outright (the cascade takes members, statuses and fields): a test's own cleanup. */
    suspend fun purge(ids: Collection<UInt>) {
        if (ids.isEmpty()) return
        suspendTransaction(sharedDatabaseForTests()) {
            RoomService.Rooms.deleteWhere { RoomService.Rooms.id inList ids }
        }
    }
}

/** A signed-in test user: the id (for fixtures and audit assertions) and a client carrying their bearer token. */
class RoomActor(val id: UInt, val client: HttpClient)

/** Seeds a unique user and logs them in; the caller soft-deletes [RoomActor.id] afterwards. */
suspend fun ApplicationTestBuilder.seedActor(prefix: String, role: UserRole = UserRole.USER, name: String = "Test"): RoomActor {
    val email = uniqueEmail(prefix)
    val id = TestUsers.seed(email = email, password = "pw", role = role, name = name)
    return RoomActor(id, authedClient(email, "pw"))
}

/**
 * The [ch.nokillswit.rooms.WorkflowUsage] fake a test registers under `WorkflowUsageKey`: fixed per-status counts, and every move it
 * is asked for recorded (and applied to the counts, as a real implementation would). Each move also records whether its target status
 * row was live at that moment, which pins the PUT's order: the upsert comes BEFORE the moves.
 */
class FakeWorkflowUsage(counts: Map<String, Long> = emptyMap()) : WorkflowUsage {
    private val counts = counts.toMutableMap()
    val moves = mutableListOf<Move>()

    /** [targetLiveAtMove]: the target status row existed and was not retired when the move ran (inside the PUT's own transaction). */
    data class Move(val roomId: UInt, val from: String, val to: String, val byUserId: UInt, val targetLiveAtMove: Boolean)

    override suspend fun threadCounts(roomId: UInt, keys: Collection<String>): Map<String, Long> =
        counts.filterKeys { it in keys }

    override suspend fun moveThreads(roomId: UInt, from: String, to: String, byUserId: UInt): Long {
        // Read through the ambient transaction (Exposed reuses it), so it sees the PUT's own uncommitted upsert - or the lack of it.
        val statuses = RoomWorkflowService.RoomStatuses
        val live = statuses.select(statuses.key)
            .where { (statuses.roomId eq roomId) and (statuses.key eq to) and (statuses.markedAsDeleted eq false) }
            .toList().isNotEmpty()
        moves += Move(roomId, from, to, byUserId, live)
        val moved = counts.remove(from) ?: 0L
        counts[to] = (counts[to] ?: 0L) + moved
        return moved
    }
}
