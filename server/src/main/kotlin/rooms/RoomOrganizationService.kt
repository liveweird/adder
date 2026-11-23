package ch.nokillswit.rooms

import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.jsonb
import ch.nokillswit.infra.json.canonicalJson
import ch.nokillswit.infra.revision.requireRevision
import ch.nokillswit.users.UserService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

private val storedJson = Json { ignoreUnknownKeys = true }
private val writtenJson = Json { encodeDefaults = true }

@Serializable
private data class StoredRoomOrganization(
    val groups: List<RoomOrganizationGroup> = emptyList(),
    val rooms: List<RoomOrganizationPlacement> = emptyList(),
)

data class RoomOrganizationReplaceResult(val response: RoomOrganizationResponse, val changed: Boolean)

object UserRoomOrganizations : Table("user_room_organizations") {
    val userId = reference("user_id", UserService.Users)
    val revision = long("revision").default(1L)
    val document = jsonb("document")
    override val primaryKey = PrimaryKey(userId)
}

class RoomOrganizationService(private val database: R2dbcDatabase) {
    suspend fun read(userId: UInt): RoomOrganizationResponse = suspendTransaction(database) {
        val row = UserRoomOrganizations.selectAll().where { UserRoomOrganizations.userId eq userId }.toList().singleOrNull()
            ?: return@suspendTransaction RoomOrganizationResponse(1, emptyList(), emptyList())
        val stored = decode(row[UserRoomOrganizations.document])
        RoomOrganizationResponse(row[UserRoomOrganizations.revision], stored.groups, visiblePlacements(userId, stored.rooms))
    }

    suspend fun replace(userId: UInt, expectedRevision: Long, request: RoomOrganizationUpdateRequest): RoomOrganizationReplaceResult =
        suspendTransaction(database) {
            val normalized = normalizedRoomOrganization(request)
            val allowed = currentRoomIds(userId, normalized.rooms.map(RoomOrganizationPlacement::roomId))
            if (normalized.rooms.any { it.roomId !in allowed }) {
                throw io.ktor.server.plugins.BadRequestException("Every organized room must be one of your rooms")
            }
            UserRoomOrganizations.insertIgnore {
                it[UserRoomOrganizations.userId] = userId
                it[revision] = 1L
                it[document] = encode(RoomOrganizationUpdateRequest(emptyList(), emptyList()))
            }
            val locked = UserRoomOrganizations.select(UserRoomOrganizations.revision, UserRoomOrganizations.document)
                .where { UserRoomOrganizations.userId eq userId }
                .forUpdate(ForUpdateOption.PostgreSQL.ForNoKeyUpdate)
                .toList()
                .single()
            val currentRevision = locked[UserRoomOrganizations.revision]
            requireRevision(expectedRevision, currentRevision)
            val current = decode(locked[UserRoomOrganizations.document])
            val encoded = encode(normalized)
            val changed = current != StoredRoomOrganization(normalized.groups, normalized.rooms)
            val nextRevision = if (changed) currentRevision + 1 else currentRevision
            if (changed) {
                UserRoomOrganizations.update({ UserRoomOrganizations.userId eq userId }) {
                    it[revision] = nextRevision
                    it[document] = encoded
                }
            }
            RoomOrganizationReplaceResult(
                RoomOrganizationResponse(nextRevision, normalized.groups, normalized.rooms),
                changed,
            )
        }

    private suspend fun visiblePlacements(userId: UInt, placements: List<RoomOrganizationPlacement>): List<RoomOrganizationPlacement> {
        if (placements.isEmpty()) return emptyList()
        val allowed = currentRoomIds(userId, placements.map(RoomOrganizationPlacement::roomId))
        return placements.filter { it.roomId in allowed }
    }

    private suspend fun currentRoomIds(userId: UInt, roomIds: Collection<UInt>): Set<UInt> {
        if (roomIds.isEmpty()) return emptySet()
        return RoomService.RoomMembers.innerJoin(RoomService.Rooms).innerJoin(UserService.Users)
            .select(RoomService.Rooms.id)
            .where {
                (RoomService.RoomMembers.userId eq userId) and (RoomService.Rooms.id inList roomIds) and
                    RoomService.Rooms.active() and UserService.Users.active()
            }
            .map { it[RoomService.Rooms.id].value }
            .toList()
            .toSet()
    }

    private fun encode(request: RoomOrganizationUpdateRequest): String = canonicalJson(
        writtenJson.encodeToString(
            StoredRoomOrganization.serializer(),
            StoredRoomOrganization(request.groups, request.rooms),
        ),
    )

    private fun decode(document: String): StoredRoomOrganization =
        storedJson.decodeFromString(StoredRoomOrganization.serializer(), document)
}

/** The persisted explicit room order, flattened group-first for the caller's bounded (2,000 item) organization document. */
internal suspend fun personalRoomOrder(userId: UInt): List<UInt> {
    val document = UserRoomOrganizations.select(UserRoomOrganizations.document)
        .where { UserRoomOrganizations.userId eq userId }
        .toList()
        .singleOrNull()
        ?.get(UserRoomOrganizations.document)
        ?: return emptyList()
    val stored = storedJson.decodeFromString(StoredRoomOrganization.serializer(), document)
    val groupOrder = stored.groups.mapIndexed { index, group -> group.id to index }.toMap()
    return stored.rooms.withIndex()
        .sortedWith(compareBy<IndexedValue<RoomOrganizationPlacement>>(
            { it.value.groupId?.let(groupOrder::get) ?: Int.MAX_VALUE },
            { it.index },
        ))
        .map { it.value.roomId }
}
