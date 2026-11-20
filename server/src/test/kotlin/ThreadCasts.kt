package ch.nokillswit

import ch.nokillswit.users.UserRole
import io.ktor.server.testing.ApplicationTestBuilder
import java.util.UUID

/**
 * The "cast" a chat test class builds per test: signed-in actors, a template and a room, minted UNIQUE (`prefix` keeps the emails apart)
 * and registered for the class's own `@AfterTest` through [MintedIds]. These are the shapes that several classes had redeclared; a
 * class whose cast is its own (an extra thread, a different set of roles, a matrix of callers) keeps a private `Cast`.
 */
class MintedIds(val users: MutableList<UInt>, val templates: MutableList<UInt>, val rooms: MutableList<UInt>)

private fun roomName(label: String) = "$label ${UUID.randomUUID().toString().substring(0, 8)}"

private suspend fun ApplicationTestBuilder.mintActor(
    minted: MintedIds,
    emailPrefix: String,
    role: UserRole = UserRole.USER,
    name: String = "Test",
): RoomActor = seedActor(emailPrefix, role, name).also { minted.users += it.id }

private suspend fun mintRoom(minted: MintedIds, owner: RoomActor, label: String): UInt {
    val template = TestThreads.seedTemplate().also { minted.templates += it }
    return TestRooms.seed(owner.id, template, roomName(label)).also { minted.rooms += it }
}

/** A room owned by `owner` with `member` in it; `outsider` is no member. */
class RoomCast(val owner: RoomActor, val member: RoomActor, val outsider: RoomActor, val room: UInt)

suspend fun ApplicationTestBuilder.seedRoomCast(prefix: String, roomLabel: String, minted: MintedIds): RoomCast {
    val owner = mintActor(minted, "$prefix-own")
    val member = mintActor(minted, "$prefix-mem")
    val outsider = mintActor(minted, "$prefix-out")
    val room = mintRoom(minted, owner, roomLabel)
    TestRooms.addMember(room, member.id)
    return RoomCast(owner, member, outsider, room)
}

/**
 * A room owned by `owner` with `ann` and `bob` as members (named `Name-own`, `Name-ann`, ...); `outsider` is no member. [thread] is the id
 * of thread #1 when [seedPairCast] opened it for `owner` (`withThread`), 0 otherwise.
 */
class PairCast(
    val owner: RoomActor,
    val ann: RoomActor,
    val bob: RoomActor,
    val outsider: RoomActor,
    val room: UInt,
    val thread: UInt = 0u,
)

suspend fun ApplicationTestBuilder.seedPairCast(
    prefix: String,
    roomLabel: String,
    minted: MintedIds,
    withThread: Boolean = false,
): PairCast {
    suspend fun actor(suffix: String) = mintActor(minted, "$prefix-$suffix", name = "Name-$suffix")
    val owner = actor("own")
    val ann = actor("ann")
    val bob = actor("bob")
    val outsider = actor("out")
    val room = mintRoom(minted, owner, roomLabel)
    TestRooms.addMember(room, ann.id)
    TestRooms.addMember(room, bob.id)
    val thread = if (withThread) TestThreads.seed(room, owner.id).id else 0u
    return PairCast(owner, ann, bob, outsider, room, thread)
}

/**
 * A room owned by Olivia with thread #1 started by `author` (Alina, a plain member), `member` (Marek) another plain member, `outsider`
 * no member and `admin` an ADMIN who is no member. [opener] is the opening message of thread #1. Open so a class can add the message
 * it posts after (`MessageReactionsTest`).
 */
open class ThreadCast(
    val owner: RoomActor,
    val author: RoomActor,
    val member: RoomActor,
    val outsider: RoomActor,
    val admin: RoomActor,
    val room: UInt,
    val threadId: UInt,
    val opener: UInt,
) {
    constructor(base: ThreadCast) : this(
        base.owner, base.author, base.member, base.outsider, base.admin, base.room, base.threadId, base.opener,
    )
}

suspend fun ApplicationTestBuilder.seedThreadCast(
    prefix: String,
    roomLabel: String,
    minted: MintedIds,
    adminName: String = "Test",
): ThreadCast {
    val owner = mintActor(minted, "$prefix-own", name = "Olivia")
    val author = mintActor(minted, "$prefix-aut", name = "Alina")
    val member = mintActor(minted, "$prefix-mem", name = "Marek")
    val outsider = mintActor(minted, "$prefix-out")
    val admin = mintActor(minted, "$prefix-adm", UserRole.ADMIN, adminName)
    val room = mintRoom(minted, owner, roomLabel)
    TestRooms.addMember(room, author.id)
    TestRooms.addMember(room, member.id)
    val thread = TestThreads.seed(room, author.id)
    return ThreadCast(owner, author, member, outsider, admin, room, thread.id, thread.messageId)
}
