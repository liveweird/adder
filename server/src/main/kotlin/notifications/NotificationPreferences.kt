package ch.nokillswit.notifications

import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.insertRows
import ch.nokillswit.users.UserService
import io.ktor.server.plugins.BadRequestException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.ArrayColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * The channels a notice can reach a person through, PERSISTED by name and frozen (`user_notification_preferences.channel` has no CHECK:
 * this enum is the whitelist, the V5 rule). Every kind has a switch for each (1.1 D7; no master switch, none is locked on). A stored name
 * this build does not know is dropped by every reader.
 */
@Serializable
enum class NotificationChannel { EMAIL, IN_APP }

/** One `(kind, channel)` pair: the stored shape, and one element of either list of the PUT body. */
@Serializable
data class NotificationPreferencePair(val type: NotificationType, val channel: NotificationChannel)

/** One kind in `GET /users/{id}/notification-preferences`: whether it shows in the app and whether it joins the email digest. */
@Serializable
data class NotificationPreferenceKind(val type: NotificationType, val inApp: Boolean, val email: Boolean)

/** Body of `GET /users/{id}/notification-preferences`. [emailAvailable] is false with no mailer (the email switches then do nothing). */
@Serializable
data class RoomMembershipNotificationPreference(val inApp: Boolean, val email: Boolean)

@Serializable
data class NotificationPreferencesResponse(
    val emailAvailable: Boolean,
    val kinds: List<NotificationPreferenceKind>,
    val roomMembership: RoomMembershipNotificationPreference,
)

/**
 * Body of `PUT /users/{id}/notification-preferences`: a wholesale replace. [disabled] lists the pairs that are OFF, [enabled] the pairs
 * that are ON, and a pair in neither takes its default (`NotificationType.defaultOn`), so a 1.0.0 client (which sends only `disabled`)
 * can never switch a default-off pair on.
 */
@Serializable
data class NotificationPreferencesUpdateRequest(
    val disabled: List<NotificationPreferencePair>,
    val enabled: List<NotificationPreferencePair> = emptyList(),
    val roomMembership: RoomMembershipNotificationPreference? = null,
)

/** Every `(kind, channel)` pair there is: a longer list than this (the two together) repeats one. The spec's `maxItems` on both lists. */
internal val MAX_PREFERENCE_PAIRS = THREAD_NOTIFICATION_TYPES.size * NotificationChannel.entries.size

/** The pair's switch is on for a person who never touched it. */
internal fun NotificationPreferencePair.defaultOn(): Boolean = type.defaultOn(channel)

/**
 * A person's choices as the PUT states them, validated: no pair twice and none in both lists (a longer list than there are pairs is a
 * repeat by the pigeonhole rule, checked first so an oversized body is refused before it is hashed). A violation is a `400`.
 */
internal class NotificationPreferenceChoice(val disabled: Set<NotificationPreferencePair>, val enabled: Set<NotificationPreferencePair>) {
    /** The DEVIATIONS this choice makes (a pair that differs from its default): `true` = switched on, `false` = switched off. */
    fun deviations(): Map<NotificationPreferencePair, Boolean> =
        disabled.filter { it.defaultOn() }.associateWith { false } + enabled.filter { !it.defaultOn() }.associateWith { true }

    /**
     * The rows a save writes: the [deviations], plus an explicit `enabled = false` row for every EMAIL pair that is switched off
     * even though
     * that is the 1.1 default. 1.1 ignores such a row (it is no deviation), but a build rolled back to 1.0.0 still reads it as "off", so
     * a person's opt-out survives the rollback instead of turning into "on" (`persistence.md` "Rollback below 1.1.0"). IN_APP stores
     * deviations only: 1.0.0 has no such channel.
     */
    fun rowsToStore(): Map<NotificationPreferencePair, Boolean> =
        deviations() + disabled.filter { it.channel == NotificationChannel.EMAIL }.associateWith { false }
}

/**
 * The PUT body's rules, run by the route AND the service. An unknown kind or channel never gets here: it fails to decode.
 */
internal fun validateNotificationPreferences(
    disabled: List<NotificationPreferencePair>,
    enabled: List<NotificationPreferencePair>,
    roomMembership: RoomMembershipNotificationPreference? = null,
): NotificationPreferenceChoice {
    val repeated = "A notification preference is listed more than once"
    if (disabled.size + enabled.size > MAX_PREFERENCE_PAIRS) throw BadRequestException(repeated)
    val off = disabled.toSet()
    val on = enabled.toSet()
    if (off.size != disabled.size || on.size != enabled.size || off.any { it in on }) throw BadRequestException(repeated)
    val membership = NotificationType.ROOM_ADDED
    if (off.any { it.type == membership } || on.any { it.type == membership }) {
        throw BadRequestException("Use roomMembership for room membership notifications")
    }
    if (roomMembership == null) return NotificationPreferenceChoice(off, on)
    val channels = NotificationChannel.entries.map { NotificationPreferencePair(membership, it) }
    val (membershipOn, membershipOff) = channels.partition { pair ->
        when (pair.channel) {
            NotificationChannel.IN_APP -> roomMembership.inApp
            NotificationChannel.EMAIL -> roomMembership.email
        }
    }
    return NotificationPreferenceChoice(off + membershipOff, on + membershipOn)
}

/**
 * What a person's switches come to: the stored deviations laid over the defaults. [deviations] holds only pairs that differ from their
 * default (`true` = on although off by default, `false` = off although on by default); a stored row that says what the default says is
 * dropped on read, so "a row that matches the default" and "no row" are one thing.
 */
internal class EffectivePreferences(private val stored: Map<NotificationPreferencePair, Boolean>) {
    fun on(type: NotificationType, channel: NotificationChannel): Boolean =
        stored[NotificationPreferencePair(type, channel)] ?: type.defaultOn(channel)

    val deviations: Map<NotificationPreferencePair, Boolean> = stored.filter { (pair, value) -> value != pair.defaultOn() }

    companion object {
        /** A person with no stored row at all. */
        val DEFAULTS = EffectivePreferences(emptyMap())
    }
}

/** Every kind with its two switches, in enum order. */
internal fun preferencesResponse(effective: EffectivePreferences, emailAvailable: Boolean): NotificationPreferencesResponse =
    NotificationPreferencesResponse(
        emailAvailable,
        THREAD_NOTIFICATION_TYPES.map {
            NotificationPreferenceKind(it, effective.on(it, NotificationChannel.IN_APP), effective.on(it, NotificationChannel.EMAIL))
        },
        RoomMembershipNotificationPreference(
            inApp = effective.on(NotificationType.ROOM_ADDED, NotificationChannel.IN_APP),
            email = effective.on(NotificationType.ROOM_ADDED, NotificationChannel.EMAIL),
        ),
    )

/** Audit format of a deviation set: sorted `TYPE:CHANNEL=on|off`, comma-joined, `""` for none. */
internal fun Map<NotificationPreferencePair, Boolean>.joinedForAudit(): String =
    map { (pair, value) -> "${pair.type.name}:${pair.channel.name}=${if (value) "on" else "off"}" }.sorted().joinToString(",")

/**
 * Per-user notification preferences (V22, reshaped by V28): the table stores the DEVIATIONS from the defaults that live in code
 * (`NotificationType.defaultOn`), each with an explicit `enabled` flag, so a new kind starts at its default for everybody without a write
 * and a row's meaning never depends on a default's code. A row whose names this build does not know is dropped by every reader (the
 * open-set rule). The digest and the notice writer read the effective set when they run, so a switch takes effect for notices not yet made
 * or mailed.
 */
internal class NotificationPreferenceService(private val database: R2dbcDatabase) {
    object Preferences : Table("user_notification_preferences") {
        val userId = reference("user_id", UserService.Users)
        val notificationType = varchar("notification_type", length = 40)
        val channel = varchar("channel", length = 10)
        val enabled = bool("enabled")
        override val primaryKey = PrimaryKey(userId, notificationType, channel)
    }

    /** The person's effective switches, or null when the id is unknown or soft-deleted (the route's `404`). */
    suspend fun read(userId: UInt): EffectivePreferences? = suspendTransaction(database) {
        val users = UserService.Users
        if (users.select(users.id).where { (users.id eq userId) and users.active() }.toList().isEmpty()) return@suspendTransaction null
        effectivePreferences(userId)
    }

    /**
     * Wholesale-replaces the person's choices and answers the switches that were in place immediately before, or null when the id is
     * unknown or soft-deleted (the route's `404`). What is stored is [NotificationPreferenceChoice.rowsToStore]: the DEVIATIONS of
     * [disabled] and [enabled] from the defaults, and an explicit "off" row for every EMAIL pair that is off (a pair in neither list takes
     * its default; a V22 "off" row therefore survives a save as an explicit row, which 1.1 ignores and a rollback honours). The
     * user row is locked `FOR NO KEY UPDATE`
     * for the read-then-write, so two replaces serialize (the second reads the first's committed state: last writer wins, and the audited
     * `from` is true) instead of interleaving into a union. A same-state PUT is a no-op replace.
     */
    suspend fun replace(
        userId: UInt,
        disabled: List<NotificationPreferencePair>,
        enabled: List<NotificationPreferencePair>,
        roomMembership: RoomMembershipNotificationPreference? = null,
    ): EffectivePreferences? {
        val rows = validateNotificationPreferences(disabled, enabled, roomMembership).rowsToStore()
        return suspendTransaction(database) {
            val users = UserService.Users
            val exists = users.select(users.id).where { (users.id eq userId) and users.active() }
                .forUpdate(ForUpdateOption.PostgreSQL.ForNoKeyUpdate).toList().isNotEmpty()
            if (!exists) return@suspendTransaction null
            val previous = effectivePreferences(userId)
            Preferences.deleteWhere { Preferences.userId eq userId }
            // A few multi-row statements (`insertRows`' binary split of its statement texts), never one statement per pair; at most
            // 26 rows including the room membership switches.
            Preferences.insertRows(rows.entries.toList()) { (pair, value) ->
                this[Preferences.userId] = userId
                this[Preferences.notificationType] = pair.type.name
                this[Preferences.channel] = pair.channel.name
                this[Preferences.enabled] = value
            }
            previous
        }
    }
}

/**
 * The switches of [userId], read INSIDE the caller's transaction (the digest's per-recipient read, one statement, no second transaction).
 */
internal suspend fun effectivePreferences(userId: UInt): EffectivePreferences {
    val p = NotificationPreferenceService.Preferences
    val known = NotificationType.entries.map { it.name }
    return EffectivePreferences(
        p.select(p.notificationType, p.channel, p.enabled).where { (p.userId eq userId) and (p.notificationType inList known) }
            .map { Triple(it[p.notificationType], it[p.channel], it[p.enabled]) }.toList()
            .mapNotNull { (type, channel, enabled) -> storedPair(type, channel)?.let { it to enabled } }.toMap(),
    )
}

/** The pair a stored row names, or null when this build does not know one of the two names (the open-set rule). */
private fun storedPair(type: String, channel: String): NotificationPreferencePair? {
    val t = NotificationType.entries.firstOrNull { it.name == type }
    val c = NotificationChannel.entries.firstOrNull { it.name == channel }
    return if (t == null || c == null) null else NotificationPreferencePair(t, c)
}

/**
 * The switches of each of [userIds] (a person with no stored row is not in the map: they take the defaults), read INSIDE the caller's
 * transaction in ONE statement of constant text (the writer's per-event read: the ids and the kinds travel as array arguments, so a
 * 499-person event is the same statement as a 2-person one). Only the pairs that are in [types] are read.
 */
internal suspend fun effectivePreferencesOf(
    userIds: Collection<UInt>,
    types: Collection<NotificationType>,
): Map<UInt, EffectivePreferences> {
    if (userIds.isEmpty() || types.isEmpty()) return emptyMap()
    val rows = TransactionManager.current().exec(
        READ_PREFERENCES,
        listOf(
            ArrayColumnType<Long, List<Long>>(LongColumnType()) to userIds.map { it.toLong() },
            ArrayColumnType<String, List<String>>(TextColumnType()) to types.map { it.name },
        ),
        explicitStatementType = StatementType.SELECT,
    ) { row ->
        Triple(
            row.get(0, Long::class.javaObjectType)!!.toUInt(),
            storedPair(row.get(1, String::class.java)!!, row.get(2, String::class.java)!!),
            row.get(3, Boolean::class.javaObjectType) == true,
        )
    }?.toList().orEmpty().filterNotNull()
    return rows.groupBy({ it.first }) { (_, pair, enabled) -> pair?.let { it to enabled } }
        .mapValues { (_, pairs) -> EffectivePreferences(pairs.filterNotNull().toMap()) }
}

private const val READ_PREFERENCES = """
SELECT user_id, notification_type, channel, enabled FROM user_notification_preferences
WHERE user_id = ANY(CAST(? AS int8[])) AND notification_type = ANY(CAST(? AS varchar[]))"""
