package ch.nokillswit

import ch.nokillswit.authz.RoomRole
import ch.nokillswit.infra.notify.LiveHint
import ch.nokillswit.infra.notify.LiveKind
import ch.nokillswit.infra.notify.notifyLive
import ch.nokillswit.infra.revision.EXPECTED_REVISION_HEADER
import ch.nokillswit.rooms.RoomCreateRequest
import ch.nokillswit.rooms.RoomFieldDef
import ch.nokillswit.rooms.RoomMemberRoleRequest
import ch.nokillswit.rooms.RoomMembersAddRequest
import ch.nokillswit.rooms.RoomStatusDef
import ch.nokillswit.rooms.RoomUpdateRequest
import ch.nokillswit.rooms.RoomWorkflowRequest
import ch.nokillswit.rooms.RoomWorkflowResponse
import ch.nokillswit.threads.MessagePostRequest
import ch.nokillswit.threads.MessageRequest
import ch.nokillswit.threads.MessageResponse
import ch.nokillswit.threads.PinRequest
import ch.nokillswit.threads.WatchRequest
import ch.nokillswit.threads.ReadMarkerRequest
import ch.nokillswit.threads.ReactionRequest
import ch.nokillswit.threads.ThreadCreateRequest
import ch.nokillswit.threads.ThreadOwnerRequest
import ch.nokillswit.threads.ThreadResponse
import ch.nokillswit.threads.ThreadStatusRequest
import ch.nokillswit.threads.ThreadTagsRequest
import ch.nokillswit.threads.ThreadUpdateRequest
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * Every chat write emits exactly ONE `adder_live` hint, on its change branch and nowhere else (0.4.0 S1, `live.md` "Hints"): the room
 * record, the members, the workflow, the thread and the message writes each carry their kind, room, thread number, message id and
 * targets; a no-op, a refused write and a rolled-back transaction emit nothing. The hints are read off a plain `LISTEN` session
 * ([NotifyCapture]) bracketed by a sentinel, so "nothing" is proven by ORDER, never by sleeping. Rooms, users and templates are shared
 * suite state: every test mints unique rows and removes them.
 */
class NotifyHintsTest {
    private val rooms = "/api/v1/rooms"
    private val mintedRooms = mutableListOf<UInt>()
    private val mintedUsers = mutableListOf<UInt>()
    private val mintedTemplates = mutableListOf<UInt>()
    private val mintedTeams = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(mintedRooms)
        mintedUsers.forEach { TestUsers.softDelete(it) }
        mintedTemplates.forEach { TestTemplates.delete(it) }
        mintedTeams.forEach { TestTeams.service.delete(it) }
    }

    private fun marker() = UUID.randomUUID().toString().substring(0, 8)

    private suspend fun ApplicationTestBuilder.actor(prefix: String, role: UserRole = UserRole.USER): RoomActor =
        seedActor(prefix, role).also { mintedUsers += it.id }

    private suspend fun template(): UInt = TestThreads.seedTemplate().also { mintedTemplates += it }

    /** A room owned by [owner] (raw-seeded: past the hint-emitting routes' clients, though the service create does emit). */
    private suspend fun room(owner: RoomActor, template: UInt): UInt =
        TestRooms.seed(owner.id, template, "Hint ${marker()}").also { mintedRooms += it }

    private fun hint(kind: LiveKind, room: UInt, thread: Int? = null, message: UInt? = null, targets: List<UInt>? = null) =
        LiveHint(kind, room, thread, message, targets)

    private suspend fun HttpClient.putRevision(path: String, revision: Long, body: Any): HttpResponse = put(path) {
        header(EXPECTED_REVISION_HEADER, revision.toString())
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    /** Asserts the answer (with the request in the failure), so a test line stays one call. */
    private fun HttpResponse.expect(expected: HttpStatusCode) =
        assertEquals(expected, status, "${request.method.value} ${request.url.encodedPath}")

    private suspend fun HttpClient.putStatus(room: UInt, status: String): HttpResponse =
        putJson("$rooms/$room/threads/1/status", ThreadStatusRequest(status))

    @Test
    fun `each room write emits exactly one hint`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("hint-own")
        val member = actor("hint-mem")
        val admin = actor("hint-adm", UserRole.ADMIN)
        val template = template()
        var room = 0u
        NotifyCapture().use { capture ->
            val hints = capture.during {
                val created = owner.client.postJson(rooms, RoomCreateRequest("Hint ${marker()}", template))
                created.expect(HttpStatusCode.Created)
                room = created.headers["Location"]!!.substringAfterLast('/').toUInt().also { mintedRooms += it }
                TestRooms.addMember(room, member.id) // raw: the delete's targets are the creator AND this member
                owner.client.putJson("$rooms/$room", RoomUpdateRequest("Hint ${marker()}")).expect(HttpStatusCode.NoContent)
                owner.client.postJson("$rooms/$room/archive", "").expect(HttpStatusCode.NoContent)
                owner.client.postJson("$rooms/$room/unarchive", "").expect(HttpStatusCode.NoContent)
                admin.client.delete("$rooms/$room").expect(HttpStatusCode.NoContent)
            }
            assertEquals(
                listOf(
                    hint(LiveKind.ROOM_CREATED, room, targets = listOf(owner.id)),
                    hint(LiveKind.ROOM_UPDATED, room),
                    hint(LiveKind.ROOM_ARCHIVED, room),
                    hint(LiveKind.ROOM_UNARCHIVED, room),
                    hint(LiveKind.ROOM_DELETED, room, targets = listOf(owner.id, member.id).sorted()),
                ),
                hints.map { if (it.kind == LiveKind.ROOM_DELETED) it.copy(targetUserIds = it.targetUserIds?.sorted()) else it },
            )
        }
    }

    @Test
    fun `member writes emit their hint with the targets`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("hint-mown")
        val ann = actor("hint-ann")
        val bob = actor("hint-bob")
        val cid = actor("hint-cid")
        val room = room(owner, template())
        val team = TestTeams.seed("hint-team-${marker()}").also { mintedTeams += it }
        TestTeams.service.addMember(team, bob.id) { }
        TestTeams.service.addMember(team, cid.id) { }
        NotifyCapture().use { capture ->
            val hints = capture.during {
                val people = owner.client.postJson("$rooms/$room/members", RoomMembersAddRequest(userIds = listOf(ann.id, bob.id)))
                people.expect(HttpStatusCode.OK)
                // The team snapshot adds only the person who is not a member yet: the targets are the ADDED users alone.
                val viaTeam = owner.client.postJson("$rooms/$room/members", RoomMembersAddRequest(teamId = team))
                viaTeam.expect(HttpStatusCode.OK)
                owner.client.putJson("$rooms/$room/members/${ann.id}", RoomMemberRoleRequest(RoomRole.OWNER))
                    .expect(HttpStatusCode.NoContent)
                owner.client.delete("$rooms/$room/members/${ann.id}").expect(HttpStatusCode.NoContent)
            }
            assertEquals(
                listOf(
                    hint(LiveKind.ROOM_MEMBER_ADDED, room, targets = listOf(ann.id, bob.id)),
                    hint(LiveKind.ROOM_MEMBER_ADDED, room, targets = listOf(cid.id)),
                    hint(LiveKind.ROOM_MEMBER_ROLE_CHANGED, room, targets = listOf(ann.id)),
                    hint(LiveKind.ROOM_MEMBER_REMOVED, room, targets = listOf(ann.id)),
                ),
                hints,
            )
        }
    }

    @Test
    fun `a read marker and a room seen write emit no hint and no audit line`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("hint-rown")
        val member = actor("hint-rmem")
        val room = room(owner, template())
        TestRooms.addMember(room, member.id)
        val opening = TestThreads.seed(room, owner.id).messageId
        withAuditCapture { audit ->
            NotifyCapture().use { capture ->
                val hints = capture.during {
                    member.client.putJson("$rooms/$room/threads/1/read-marker", ReadMarkerRequest(opening)).expect(HttpStatusCode.NoContent)
                    member.client.postJson("$rooms/$room/seen", "").expect(HttpStatusCode.NoContent)
                    member.client.postJson("$rooms/$room/seen", "").expect(HttpStatusCode.NoContent)
                }
                assertEquals(emptyList(), hints, "view state announces nothing")
            }
            assertTrue(audit.events.isEmpty(), "view state is not audited: ${audit.events.map { it.message }}")
        }
    }

    @Test
    fun `thread and message writes emit their hint with the thread number`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("hint-town")
        val member = actor("hint-tmem")
        val room = room(owner, template())
        TestRooms.addMember(room, member.id)
        NotifyCapture().use { capture ->
            val messageId = arrayOfNulls<UInt>(1)
            val hints = capture.during {
                val created = member.client.postJson(
                    "$rooms/$room/threads",
                    ThreadCreateRequest("Hint thread", "Opening", mapOf("severity" to JsonPrimitive("s1"))),
                )
                created.expect(HttpStatusCode.Created)
                assertEquals(1, created.body<ThreadResponse>().number)
                val details = ThreadUpdateRequest("Hint thread, renamed", mapOf("severity" to JsonPrimitive("s1")))
                member.client.putRevision("$rooms/$room/threads/1", 1, details).expect(HttpStatusCode.NoContent)
                member.client.putStatus(room, "monitoring").expect(HttpStatusCode.NoContent)
                owner.client.putJson("$rooms/$room/threads/1/owner", ThreadOwnerRequest(owner.id)).expect(HttpStatusCode.NoContent)
                val posted = member.client.postJson("$rooms/$room/threads/1/messages", MessageRequest("A reply"))
                posted.expect(HttpStatusCode.Created)
                messageId[0] = posted.body<MessageResponse>().id
                member.client.putJson("$rooms/$room/messages/${messageId[0]}", MessageRequest("An edited reply"))
                    .expect(HttpStatusCode.NoContent)
                member.client.delete("$rooms/$room/messages/${messageId[0]}").expect(HttpStatusCode.NoContent)
            }
            val message = messageId[0]!!
            val opening = TestThreads.rawMessages(TestThreads.rawAll(room).single().id).first().id
            // The opening message is part of `thread.created`: no `message.posted` for it. What each write told people follows its own
            // hint: the room hears of the thread (the owner), the creator hears the thread change hands, the new owner hears the reply.
            // The status move tells the watchers but the creator, who moved it, and the details and the edit tell nobody.
            assertEquals(
                listOf(
                    hint(LiveKind.THREAD_CREATED, room, thread = 1),
                    hint(LiveKind.NOTIFICATION_CREATED, room, thread = 1, message = opening, targets = listOf(owner.id)),
                    hint(LiveKind.THREAD_UPDATED, room, thread = 1),
                    hint(LiveKind.THREAD_STATUS_CHANGED, room, thread = 1),
                    hint(LiveKind.THREAD_OWNER_CHANGED, room, thread = 1),
                    hint(LiveKind.NOTIFICATION_CREATED, room, thread = 1, targets = listOf(member.id)),
                    hint(LiveKind.MESSAGE_POSTED, room, thread = 1, message = message),
                    hint(LiveKind.NOTIFICATION_CREATED, room, thread = 1, message = message, targets = listOf(owner.id)),
                    hint(LiveKind.MESSAGE_EDITED, room, thread = 1, message = message),
                    hint(LiveKind.MESSAGE_DELETED, room, thread = 1, message = message),
                ),
                hints,
            )
        }
    }

    @Test
    fun `a tag save emits one thread updated hint on a change, none for the same set or a refusal, and tells nobody`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("hint-tgown")
        val member = actor("hint-tgmem")
        val room = room(owner, template())
        TestRooms.addMember(room, member.id)
        TestThreads.seed(room, owner.id)
        val tagsPath = "$rooms/$room/threads/1/tags"
        NotifyCapture().use { capture ->
            val hints = capture.during {
                // The seeded Kind tag is live in every database (V32); no test touches the seeded categories.
                member.client.putRevision(tagsPath, 1, ThreadTagsRequest(listOf("question"))).expect(HttpStatusCode.NoContent)
                member.client.putRevision(tagsPath, 2, ThreadTagsRequest(listOf("question"))).expect(HttpStatusCode.NoContent) // same set
                member.client.putRevision(tagsPath, 1, ThreadTagsRequest(emptyList())).expect(HttpStatusCode.Conflict) // stale
                member.client.putRevision(tagsPath, 2, ThreadTagsRequest(listOf("no-such-tag"))).expect(HttpStatusCode.BadRequest)
                owner.client.putRevision(tagsPath, 2, ThreadTagsRequest(emptyList())).expect(HttpStatusCode.NoContent)
            }
            // `thread.updated` only: a tag change is history, not activity, so no notice hint follows it (the pin rule, 1.2 A4).
            assertEquals(listOf(hint(LiveKind.THREAD_UPDATED, room, thread = 1), hint(LiveKind.THREAD_UPDATED, room, thread = 1)), hints)
        }
    }

    @Test
    fun `a pin and an unpin emit one hint each, with the thread number and, for a message, its id`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("hint-pown")
        val member = actor("hint-pmem")
        val room = room(owner, template())
        TestRooms.addMember(room, member.id)
        TestThreads.seed(room, member.id)
        val message = TestThreads.post(room, 1, member.id, "Worth keeping")
        NotifyCapture().use { capture ->
            val hints = capture.during {
                member.client.putJson("$rooms/$room/threads/1/pin", PinRequest(true)).expect(HttpStatusCode.NoContent)
                member.client.putJson("$rooms/$room/messages/$message/pin", PinRequest(true)).expect(HttpStatusCode.NoContent)
                member.client.putJson("$rooms/$room/messages/$message/pin", PinRequest(true)).expect(HttpStatusCode.NoContent) // again
                owner.client.putJson("$rooms/$room/messages/$message/pin", PinRequest(false)).expect(HttpStatusCode.NoContent)
                owner.client.putJson("$rooms/$room/threads/1/pin", PinRequest(false)).expect(HttpStatusCode.NoContent)
                owner.client.putJson("$rooms/$room/threads/1/pin", PinRequest(false)).expect(HttpStatusCode.NoContent) // again
            }
            assertEquals(
                listOf(
                    hint(LiveKind.ROOM_PIN_CHANGED, room, thread = 1),
                    hint(LiveKind.ROOM_PIN_CHANGED, room, thread = 1, message = message),
                    hint(LiveKind.ROOM_PIN_CHANGED, room, thread = 1, message = message),
                    hint(LiveKind.ROOM_PIN_CHANGED, room, thread = 1),
                ),
                hints,
            )
        }
    }

    @Test
    fun `a reaction emits one hint per change with the thread number and the message id, and a refusal or a repeat emits none`() =
        testApplication {
            usePostgresTestcontainer()
            val owner = actor("hint-rxown")
            val member = actor("hint-rxmem")
            val outsider = actor("hint-rxout")
            val room = room(owner, template())
            TestRooms.addMember(room, member.id)
            TestThreads.seed(room, member.id)
            val message = TestThreads.post(room, 1, member.id, "React to me")
            val thumbsUp = "\uD83D\uDC4D"
            suspend fun react(who: RoomActor, emoji: String, reacted: Boolean) =
                who.client.putJson("$rooms/$room/messages/$message/reaction", ReactionRequest(emoji, reacted))
            NotifyCapture().use { capture ->
                val hints = capture.during {
                    react(member, thumbsUp, true).expect(HttpStatusCode.NoContent)
                    // The same state again, a bad emoji and a non-member: answered, nothing announced.
                    react(member, thumbsUp, true).expect(HttpStatusCode.NoContent)
                    react(member, "a", true).expect(HttpStatusCode.BadRequest)
                    react(outsider, thumbsUp, true).expect(HttpStatusCode.NotFound)
                    react(owner, thumbsUp, true).expect(HttpStatusCode.NoContent)
                    react(member, thumbsUp, false).expect(HttpStatusCode.NoContent)
                    // A closed thread refuses: nothing is announced.
                    TestThreads.closeRaw(room, 1)
                    react(member, thumbsUp, false).expect(HttpStatusCode.Conflict)
                }
                val changed = hint(LiveKind.MESSAGE_REACTION_CHANGED, room, thread = 1, message = message)
                assertEquals(listOf(changed, changed, changed), hints, "the add, the owner's add and the removal")
            }
        }

    @Test
    fun `an edit that only changes a tag still emits one message edited and the tag write emits no hint of its own`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("hint-tagown")
        val member = actor("hint-tagmem")
        val room = room(owner, template())
        TestRooms.addMember(room, member.id)
        TestThreads.seed(room, member.id)
        val message = TestThreads.post(room, 1, member.id, "first #alpha")
        NotifyCapture().use { capture ->
            val hints = capture.during {
                // The words are the same and only the tag changes: a hashtag edit is a message edit, so the SPA's refetch of the
                // thread list (which the tag filter reads) rides the existing hint.
                member.client.putJson("$rooms/$room/messages/$message", MessageRequest("first #beta")).expect(HttpStatusCode.NoContent)
            }
            assertEquals(listOf(hint(LiveKind.MESSAGE_EDITED, room, thread = 1, message = message)), hints)
        }
    }

    @Test
    fun `an edit that only changes a mention still emits one message edited and the mention write emits no hint of its own`() =
        testApplication {
            usePostgresTestcontainer()
            val owner = actor("hint-menown")
            val member = actor("hint-menmem")
            val room = room(owner, template())
            TestRooms.addMember(room, member.id)
            TestThreads.seed(room, member.id)
            val message = TestThreads.post(room, 1, member.id, "first <@${owner.id}>")
            NotifyCapture().use { capture ->
                val hints = capture.during {
                    // The words are the same and only the mention changes: the SPA's refetch of the message rides the existing hint.
                    member.client.putJson("$rooms/$room/messages/$message", MessageRequest("first <@${member.id}>"))
                        .expect(HttpStatusCode.NoContent)
                }
                assertEquals(listOf(hint(LiveKind.MESSAGE_EDITED, room, thread = 1, message = message)), hints)
            }
        }

    @Test
    fun `a post that mentions someone emits message posted and then notification created for the mentioned alone`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("hint-nown2")
        val author = actor("hint-nauth")
        val bob = actor("hint-nbob")
        val room = room(owner, template())
        TestRooms.addMember(room, author.id)
        TestRooms.addMember(room, bob.id)
        TestThreads.seed(room, author.id)
        NotifyCapture().use { capture ->
            val messageId = arrayOfNulls<UInt>(1)
            val hints = capture.during {
                // The author is mentioned too: she is shown, never notified, so the targets are bob alone.
                val posted = author.client.postJson("$rooms/$room/threads/1/messages", MessageRequest("hi <@${author.id}> <@${bob.id}>"))
                posted.expect(HttpStatusCode.Created)
                messageId[0] = posted.body<MessageResponse>().id
                // A post that mentions nobody is ONE hint, as before.
                author.client.postJson("$rooms/$room/threads/1/messages", MessageRequest("no mention")).expect(HttpStatusCode.Created)
            }
            val first = messageId[0]!!
            assertEquals(3, hints.size, "the mentioning post emits two hints, the plain one")
            assertEquals(
                listOf(
                    hint(LiveKind.MESSAGE_POSTED, room, thread = 1, message = first),
                    hint(LiveKind.NOTIFICATION_CREATED, room, thread = 1, message = first, targets = listOf(bob.id)),
                ),
                hints.take(2),
                "the wire order is the write's own hint, then what it caused",
            )
            assertEquals(LiveKind.MESSAGE_POSTED, hints[2].kind)
        }
    }

    @Test
    fun `a quoting post emits its one message posted hint and a refused quote emits nothing`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("hint-qown")
        val author = actor("hint-qaut")
        val room = room(owner, template())
        TestRooms.addMember(room, author.id)
        TestThreads.seed(room, author.id)
        val quoted = TestThreads.post(room, 1, owner.id, "The quoted words")
        val path = "$rooms/$room/threads/1/messages"
        NotifyCapture().use { capture ->
            val posted = arrayOfNulls<UInt>(1)
            val hints = capture.during {
                // No notice rides a quote yet (1.3 S5): one `message.posted`, like any post.
                val ok = author.client.postJson(path, MessagePostRequest("An answer", quoted))
                ok.expect(HttpStatusCode.Created)
                posted[0] = ok.body<MessageResponse>().id
                author.client.postJson(path, MessagePostRequest("To nothing", quoted + 1_000_000u)).expect(HttpStatusCode.BadRequest)
                author.client.postJson(path, MessagePostRequest("To zero", 0u)).expect(HttpStatusCode.BadRequest)
            }
            assertEquals(listOf(hint(LiveKind.MESSAGE_POSTED, room, thread = 1, message = posted[0])), hints, "a refusal hints nothing")
        }
    }

    @Test
    fun `a thread, an edit and a hand-over emit their own hint and then the notice's, a delete none`() =
        testApplication {
            usePostgresTestcontainer()
            val owner = actor("hint-aown")
            val ann = actor("hint-aann")
            val bob = actor("hint-abob")
            val room = room(owner, template())
            TestRooms.addMember(room, ann.id)
            TestRooms.addMember(room, bob.id)
            NotifyCapture().use { capture ->
                val messageIds = arrayOfNulls<UInt>(1)
                val hints = capture.during {
                    val body = ThreadCreateRequest("Mention", "Opening <@${bob.id}>", mapOf("severity" to JsonPrimitive("s1")))
                    val created = ann.client.postJson("$rooms/$room/threads", body)
                    created.expect(HttpStatusCode.Created)
                    // Ann is the only watcher: her reply tells nobody.
                    val posted = ann.client.postJson("$rooms/$room/threads/1/messages", MessageRequest("A reply"))
                    messageIds[0] = posted.body<MessageResponse>().id
                    // The edit newly mentions the owner: message edited, then the notice. An unchanged mention adds nothing.
                    ann.client.putJson("$rooms/$room/messages/${messageIds[0]}", MessageRequest("A reply <@${owner.id}>"))
                        .expect(HttpStatusCode.NoContent)
                    ann.client.putJson("$rooms/$room/messages/${messageIds[0]}", MessageRequest("A reply again <@${owner.id}>"))
                        .expect(HttpStatusCode.NoContent)
                    ann.client.delete("$rooms/$room/messages/${messageIds[0]}").expect(HttpStatusCode.NoContent)
                    // The hand-over: thread owner changed, then ONE notice hint (the new owner first, then the other watcher); taking the
                    // thread oneself tells the watchers only.
                    owner.client.putJson("$rooms/$room/threads/1/owner", ThreadOwnerRequest(bob.id)).expect(HttpStatusCode.NoContent)
                    owner.client.putJson("$rooms/$room/threads/1/owner", ThreadOwnerRequest(owner.id)).expect(HttpStatusCode.NoContent)
                }
                val message = messageIds[0]!!
                val opening = TestThreads.rawMessages(TestThreads.rawAll(room).single().id).first().id
                assertEquals(
                    listOf(
                        hint(LiveKind.THREAD_CREATED, room, thread = 1),
                        // The mention first, then the rest of the room (everyone but the author) by user id.
                        hint(LiveKind.NOTIFICATION_CREATED, room, thread = 1, message = opening, targets = listOf(bob.id, owner.id)),
                        hint(LiveKind.MESSAGE_POSTED, room, thread = 1, message = message),
                        hint(LiveKind.MESSAGE_EDITED, room, thread = 1, message = message),
                        hint(LiveKind.NOTIFICATION_CREATED, room, thread = 1, message = message, targets = listOf(owner.id)),
                        hint(LiveKind.MESSAGE_EDITED, room, thread = 1, message = message),
                        hint(LiveKind.MESSAGE_DELETED, room, thread = 1, message = message),
                        hint(LiveKind.THREAD_OWNER_CHANGED, room, thread = 1),
                        hint(LiveKind.NOTIFICATION_CREATED, room, thread = 1, targets = listOf(bob.id, ann.id)),
                        hint(LiveKind.THREAD_OWNER_CHANGED, room, thread = 1),
                        hint(LiveKind.NOTIFICATION_CREATED, room, thread = 1, targets = listOf(ann.id, bob.id)),
                    ),
                    hints,
                )
            }
        }

    @Test
    fun `a no-op write emits nothing`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("hint-nown")
        val member = actor("hint-nmem")
        val room = room(owner, template())
        TestRooms.addMember(room, member.id)
        val thread = TestThreads.seed(room, member.id, title = "Steady")
        val posted = TestThreads.post(room, 1, member.id, "Same words")
        NotifyCapture().use { capture ->
            val hints = capture.during {
                // Every call below is answered, none changes anything; the three REAL writes among them are the only hints expected.
                owner.client.postJson("$rooms/$room/archive", "").expect(HttpStatusCode.NoContent) // real
                owner.client.postJson("$rooms/$room/archive", "").expect(HttpStatusCode.NoContent) // archive twice
                owner.client.postJson("$rooms/$room/unarchive", "").expect(HttpStatusCode.NoContent) // real
                // the same role
                owner.client.putJson("$rooms/$room/members/${member.id}", RoomMemberRoleRequest(RoomRole.MEMBER))
                    .expect(HttpStatusCode.NoContent)
                // an existing member
                owner.client.postJson("$rooms/$room/members", RoomMembersAddRequest(userIds = listOf(member.id)))
                    .expect(HttpStatusCode.OK)
                val same = ThreadUpdateRequest("Steady", mapOf("severity" to JsonPrimitive("s1")))
                member.client.putRevision("$rooms/$room/threads/1", 1, same).expect(HttpStatusCode.NoContent) // unchanged details
                member.client.putStatus(room, "open").expect(HttpStatusCode.NoContent) // the same status
                // the same owner
                owner.client.putJson("$rooms/$room/threads/1/owner", ThreadOwnerRequest(member.id)).expect(HttpStatusCode.NoContent)
                // an unchanged edit
                member.client.putJson("$rooms/$room/messages/$posted", MessageRequest("Same words")).expect(HttpStatusCode.NoContent)
                member.client.putStatus(room, "monitoring").expect(HttpStatusCode.NoContent) // real
            }
            assertEquals(
                listOf(
                    hint(LiveKind.ROOM_ARCHIVED, room),
                    hint(LiveKind.ROOM_UNARCHIVED, room),
                    hint(LiveKind.THREAD_STATUS_CHANGED, room, thread = 1),
                ),
                hints,
            )
        }
        assertEquals(1, thread.number)
    }

    @Test
    fun `a refused write emits nothing`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("hint-rown")
        val member = actor("hint-rmem")
        val outsider = actor("hint-rout")
        val room = room(owner, template())
        TestRooms.addMember(room, member.id)
        TestThreads.seed(room, member.id)
        NotifyCapture().use { capture ->
            val hints = capture.during {
                // A non-member: the uniform 404.
                outsider.client.putJson("$rooms/$room", RoomUpdateRequest("Stolen ${marker()}")).expect(HttpStatusCode.NotFound)
                outsider.client.postJson("$rooms/$room/threads/1/messages", MessageRequest("Hi")).expect(HttpStatusCode.NotFound)
                // A closed thread: 409 thread-closed.
                TestThreads.closeRaw(room, 1)
                member.client.postJson("$rooms/$room/threads/1/messages", MessageRequest("Late")).expect(HttpStatusCode.Conflict)
                // An archived room: 409 room-archived.
                TestRooms.archive(room)
                member.client.postJson("$rooms/$room/threads/1/messages", MessageRequest("Later")).expect(HttpStatusCode.Conflict)
                owner.client.putJson("$rooms/$room", RoomUpdateRequest("Renamed ${marker()}")).expect(HttpStatusCode.Conflict)
            }
            assertEquals(emptyList(), hints)
        }
    }

    @Test
    fun `a rolled-back transaction emits nothing and a committed one does`() = runBlocking {
        val database = sharedDatabaseForTests()
        val lost = hint(LiveKind.ROOM_UPDATED, 71u)
        val kept = hint(LiveKind.ROOM_ARCHIVED, 72u)
        NotifyCapture().use { capture ->
            val hints = capture.during {
                runCatching {
                    suspendTransaction(database) {
                        notifyLive(lost)
                        error("rolled back on purpose")
                    }
                }
                suspendTransaction(database) { notifyLive(kept) }
            }
            assertEquals(listOf(kept), hints)
        }
    }

    @Test
    fun `the largest hint, 500 added members, is delivered whole`() = runBlocking {
        val worst = hint(LiveKind.ROOM_MEMBER_ADDED, UInt.MAX_VALUE, targets = List(500) { UInt.MAX_VALUE - it.toUInt() })
        NotifyCapture().use { capture ->
            assertEquals(listOf(worst), capture.during { suspendTransaction(sharedDatabaseForTests()) { notifyLive(worst) } })
        }
    }

    @Test
    fun `the widest notice hint, 500 recipients (an ADMIN outside the room acts), is delivered whole`() = runBlocking {
        val worst = hint(
            LiveKind.NOTIFICATION_CREATED, UInt.MAX_VALUE, thread = Int.MAX_VALUE, message = UInt.MAX_VALUE,
            targets = List(500) { UInt.MAX_VALUE - it.toUInt() },
        )
        NotifyCapture().use { capture ->
            val hints = capture.during { suspendTransaction(sharedDatabaseForTests()) { notifyLive(worst) } }
            assertEquals(listOf(worst), hints)
        }
    }

    private fun statusNotices(userId: UInt, room: UInt): Int = PostgresTestSupport.connect().use { c ->
        val sql = "SELECT count(*) FROM notifications " +
            "WHERE recipient_id = ? AND room_id = ? AND notification_type = 'THREAD_STATUS_CHANGED'"
        c.prepareStatement(sql).use { st ->
            st.setLong(1, userId.toLong())
            st.setInt(2, room.toInt())
            st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    @Test
    fun `the workflow PUT emits one hint even when it moves threads`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("hint-wown")
        val watcher = actor("hint-wwat")
        val room = room(owner, template())
        TestRooms.addMember(room, watcher.id)
        TestThreads.seed(room, owner.id)
        // A second member who WATCHES the thread: if the workflow PUT's `moveThreads` told watchers, a notice hint would follow.
        watcher.client.putJson("$rooms/$room/threads/1/watch", WatchRequest(true)).expect(HttpStatusCode.NoContent)
        owner.client.putStatus(room, "monitoring").expect(HttpStatusCode.NoContent)
        assertEquals(1, statusNotices(watcher.id, room))
        val current: RoomWorkflowResponse = owner.client.get("$rooms/$room/workflow").body()
        // Retire `monitoring`, which the thread sits in, and move it to `open`.
        val slim = RoomWorkflowRequest(
            statuses = current.statuses.filter { it.key != "monitoring" }
                .map { RoomStatusDef(it.key, it.label, it.colour, it.category, it.initial) },
            fields = current.fields.map { RoomFieldDef(it.key, it.label, it.type, it.required, it.options) },
            replacements = mapOf("monitoring" to "open"),
        )
        NotifyCapture().use { capture ->
            val hints = capture.during {
                owner.client.putRevision("$rooms/$room/workflow", current.revision, slim).expect(HttpStatusCode.NoContent)
            }
            assertEquals(listOf(hint(LiveKind.ROOM_WORKFLOW_UPDATED, room)), hints)
        }
        assertEquals("open", TestThreads.raw(room, 1)!!.statusKey)
        assertEquals(1, statusNotices(watcher.id, room), "only the owner's own move was told; the workflow PUT's move told nobody")
    }
}
