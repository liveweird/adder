package ch.nokillswit

import ch.nokillswit.threads.Notice
import ch.nokillswit.threads.NoticeAudience
import ch.nokillswit.threads.MentionGroupAudience
import ch.nokillswit.threads.NoticeReason
import ch.nokillswit.threads.NoticeReason.EVERYONE_MENTIONED
import ch.nokillswit.threads.NoticeReason.HERE_MENTIONED
import ch.nokillswit.threads.NoticeReason.MENTIONED
import ch.nokillswit.threads.NoticeReason.MESSAGE_QUOTED
import ch.nokillswit.threads.NoticeReason.TEAM_MENTIONED
import ch.nokillswit.threads.NoticeReason.WATCHERS_MENTIONED
import ch.nokillswit.threads.NoticeReason.THREAD_ASSIGNED
import ch.nokillswit.threads.NoticeReason.THREAD_CLOSED
import ch.nokillswit.threads.NoticeReason.THREAD_CREATED
import ch.nokillswit.threads.NoticeReason.THREAD_OWNER_CHANGED
import ch.nokillswit.threads.NoticeReason.THREAD_REOPENED
import ch.nokillswit.threads.NoticeReason.THREAD_REPLIED
import ch.nokillswit.threads.NoticeReason.THREAD_STATUS_CHANGED
import ch.nokillswit.threads.NoticeRevision
import ch.nokillswit.threads.StatusTransition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pure audience kernel (`chat-model.md` "Watching, group mentions and notice kinds (1.1)"): who is told about an event and why, in
 * priority order, each person once with their most specific reason, never the actor. No database: every case is a table row.
 */
class NoticeAudienceTest {
    // Users: 1 is the author or actor everywhere; 2..6 are other members.
    private val actor = 1u
    private val members = listOf(1u, 2u, 3u, 4u, 5u, 6u)

    private fun told(notices: List<Notice>): List<Pair<UInt, NoticeReason>> = notices.map { it.userId to it.reason }

    private class MessageCase(
        val name: String,
        val opening: Boolean,
        val mentioned: List<UInt>,
        val watchers: List<UInt>,
        val expected: List<Pair<UInt, NoticeReason>>,
    )

    private val messageCases = listOf(
        MessageCase("an opening message tells every member but the author", true, emptyList(), listOf(1u), listOf(
            2u to THREAD_CREATED, 3u to THREAD_CREATED, 4u to THREAD_CREATED, 5u to THREAD_CREATED, 6u to THREAD_CREATED,
        )),
        MessageCase("an opening message: a direct mention wins for that member, listed first", true, listOf(4u), emptyList(), listOf(
            4u to MENTIONED, 2u to THREAD_CREATED, 3u to THREAD_CREATED, 5u to THREAD_CREATED, 6u to THREAD_CREATED,
        )),
        MessageCase("an opening message that mentions only the author tells the others as usual", true, listOf(1u), emptyList(), listOf(
            2u to THREAD_CREATED, 3u to THREAD_CREATED, 4u to THREAD_CREATED, 5u to THREAD_CREATED, 6u to THREAD_CREATED,
        )),
        MessageCase("a reply tells the watchers but not the author", false, emptyList(), listOf(1u, 2u, 3u), listOf(
            2u to THREAD_REPLIED, 3u to THREAD_REPLIED,
        )),
        MessageCase("a reply tells a non-watcher nothing", false, emptyList(), emptyList(), emptyList()),
        MessageCase("a reply: a mention wins over the watch, a mentioned non-watcher is told, order is the priority", false, listOf(5u, 2u),
            listOf(2u, 3u), listOf(5u to MENTIONED, 2u to MENTIONED, 3u to THREAD_REPLIED)),
        MessageCase("a reply that mentions the whole audience tells each once", false, listOf(2u, 3u), listOf(2u, 3u), listOf(
            2u to MENTIONED, 3u to MENTIONED,
        )),
    )

    @Test
    fun `a post and an opening message, case by case`() {
        for (case in messageCases) {
            val audience = NoticeAudience.ofMessage(case.opening, actor, case.mentioned, case.watchers, members)
            assertEquals(case.expected, told(audience), case.name)
            assertTrue(audience.none { it.userId == actor }, "${case.name}: the author is never told")
            assertEquals(audience.size, audience.map { it.userId }.toSet().size, "${case.name}: one notice per person")
        }
    }

    @Test
    fun `each person carries the other reasons that apply, in priority order, for the writer to fall back on`() {
        fun fallbacks(notices: List<Notice>) = notices.associate { it.userId to (it.reason to it.fallbacks) }
        // A reply: 2 is mentioned and watches (MENTIONED, then replied); 5 is only mentioned; 3 only watches.
        assertEquals(
            mapOf(
                2u to (MENTIONED to listOf(THREAD_REPLIED)),
                5u to (MENTIONED to emptyList()),
                3u to (THREAD_REPLIED to emptyList()),
            ),
            fallbacks(NoticeAudience.ofMessage(false, actor, listOf(2u, 5u), listOf(2u, 3u), members)),
        )
        // An opening message: the mentioned member falls back to "started a thread".
        assertEquals(
            (MENTIONED to listOf(THREAD_CREATED)),
            fallbacks(NoticeAudience.ofMessage(true, actor, listOf(4u), emptyList(), members))[4u],
        )
        // A hand-over: the new owner (a watcher) falls back to "owner changed".
        assertEquals(
            (THREAD_ASSIGNED to listOf(THREAD_OWNER_CHANGED)),
            fallbacks(NoticeAudience.ofHandOver(actor, 4u, listOf(2u, 4u)))[4u],
        )
    }

    @Test
    fun `a status move tells the watchers by transition and never the actor`() {
        val watchers = listOf(1u, 2u, 3u)
        val kinds = mapOf(
            StatusTransition.MOVED to THREAD_STATUS_CHANGED,
            StatusTransition.CLOSED to THREAD_CLOSED,
            StatusTransition.REOPENED to THREAD_REOPENED,
        )
        assertEquals(StatusTransition.entries.toSet(), kinds.keys, "every transition is covered")
        kinds.forEach { (transition, reason) ->
            assertEquals(
                listOf(2u to reason, 3u to reason),
                told(NoticeAudience.ofStatusMove(transition, actor, watchers)),
                "$transition",
            )
        }
        assertEquals(emptyList(), told(NoticeAudience.ofStatusMove(StatusTransition.CLOSED, actor, listOf(1u))), "only the actor watches")
        assertEquals(emptyList(), told(NoticeAudience.ofStatusMove(StatusTransition.CLOSED, actor, emptyList())), "nobody watches")
    }

    @Test
    fun `a hand-over tells the new owner assigned and the other watchers owner changed`() {
        // The new owner (4) is a watcher already: assigned wins over owner changed, listed first.
        assertEquals(
            listOf(4u to THREAD_ASSIGNED, 2u to THREAD_OWNER_CHANGED, 3u to THREAD_OWNER_CHANGED),
            told(NoticeAudience.ofHandOver(actor, 4u, listOf(1u, 2u, 3u, 4u))),
        )
        // The new owner is told even if the watch is missing; the actor (a watcher) is not told.
        assertEquals(listOf(4u to THREAD_ASSIGNED), told(NoticeAudience.ofHandOver(actor, 4u, listOf(1u))))
        // Taking the thread oneself: no assigned notice for the actor, the others still hear it.
        assertEquals(
            listOf(2u to THREAD_OWNER_CHANGED),
            told(NoticeAudience.ofHandOver(actor, actor, listOf(1u, 2u))),
        )
    }

    private class EditCase(
        val name: String,
        val opening: Boolean,
        val existing: Map<UInt, NoticeReason>,
        val mentioned: List<UInt>,
        val watchers: List<UInt>,
        /** The people the edit newly mentions: mentioned by the new body, not by the old one (1.1 A34). */
        val added: List<UInt> = emptyList(),
        val add: List<Pair<UInt, NoticeReason>> = emptyList(),
        val relabel: List<Pair<UInt, NoticeReason>> = emptyList(),
        val remove: List<UInt> = emptyList(),
    )

    private val editCases = listOf(
        EditCase(
            "none, newly mentioned: add",
            false, emptyMap(), listOf(2u), listOf(2u, 3u), added = listOf(2u), add = listOf(2u to MENTIONED),
        ),
        EditCase(
            "none, newly mentioned though not a watcher: add",
            false, emptyMap(), listOf(5u), emptyList(), added = listOf(5u), add = listOf(5u to MENTIONED),
        ),
        EditCase("a mention KEPT whose notice is gone (retention, a rejoin): none", false, emptyMap(), listOf(2u), listOf(2u)),
        EditCase("a reply notice held for a KEPT mention (muted kind): stays", false, mapOf(2u to THREAD_REPLIED), listOf(2u), listOf(2u)),
        EditCase(
            "a reply notice, now mentioned: delete and insert, a fresh notice",
            false, mapOf(2u to THREAD_REPLIED), listOf(2u), listOf(2u),
            added = listOf(2u), add = listOf(2u to MENTIONED), remove = listOf(2u),
        ),
        EditCase(
            "an opening notice, now mentioned: delete and insert",
            true, mapOf(3u to THREAD_CREATED), listOf(3u), emptyList(),
            added = listOf(3u), add = listOf(3u to MENTIONED), remove = listOf(3u),
        ),
        EditCase("a mention kept: nothing", false, mapOf(2u to MENTIONED), listOf(2u), listOf(2u)),
        EditCase(
            "a mention dropped, still watching a reply: falls back to replied (relabelled in place)",
            false, mapOf(2u to MENTIONED), emptyList(), listOf(2u),
            relabel = listOf(2u to THREAD_REPLIED),
        ),
        EditCase(
            "a mention dropped, not a watcher: deleted (0.6 A6)",
            false, mapOf(5u to MENTIONED), emptyList(), listOf(2u),
            remove = listOf(5u),
        ),
        EditCase(
            "a mention dropped on the opening message: falls back to created",
            true, mapOf(5u to MENTIONED), emptyList(), emptyList(),
            relabel = listOf(5u to THREAD_CREATED),
        ),
        EditCase(
            "a mention dropped on the opening message by a member who is gone: deleted",
            true, mapOf(9u to MENTIONED), emptyList(), emptyList(),
            remove = listOf(9u),
        ),
        EditCase("a base notice, not mentioned: kept", false, mapOf(2u to THREAD_REPLIED), emptyList(), emptyList()),
        EditCase("an unwatched reply notice, not mentioned: kept", false, mapOf(2u to THREAD_REPLIED), emptyList(), listOf(3u)),
        EditCase("an edit as such adds no base notice", true, emptyMap(), emptyList(), emptyList()),
        EditCase("a reply edit as such adds no base notice", false, emptyMap(), emptyList(), listOf(2u, 3u)),
        EditCase(
            "a mixed edit: one added, one relabelled, one deleted, one kept",
            false, mapOf(2u to MENTIONED, 3u to MENTIONED, 4u to THREAD_REPLIED), listOf(3u, 6u), listOf(2u, 4u),
            added = listOf(6u), add = listOf(6u to MENTIONED), relabel = listOf(2u to THREAD_REPLIED),
        ),
    )

    @Test
    fun `an edit against the notices the message already holds, case by case`() {
        for (case in editCases) {
            val before = case.mentioned.filter { it !in case.added }.associateWith { MENTIONED }
            val revision = NoticeAudience.ofEdit(case.opening, actor, case.existing, case.mentioned, before, case.watchers, members)
            assertEquals(case.add, told(revision.add), "${case.name}: add")
            assertEquals(case.relabel, told(revision.relabel), "${case.name}: relabel")
            assertEquals(case.remove.toSet(), revision.remove.toSet(), "${case.name}: remove")
            val kept = case.existing.keys - revision.remove.toSet()
            assertTrue(revision.add.none { it.userId in kept }, "${case.name}: nobody who keeps a notice is counted again")
            assertEquals(case.add.isEmpty() && case.relabel.isEmpty() && case.remove.isEmpty(), revision.isEmpty, case.name)
        }
        assertTrue(NoticeRevision().isEmpty)
    }

    @Test
    fun `the author is never in an edit's revision`() {
        val revision = NoticeAudience.ofEdit(false, actor, emptyMap(), listOf(1u, 2u), emptyMap(), listOf(1u), members)
        assertEquals(listOf(2u to MENTIONED), told(revision.add))
    }

    private class GroupMessageCase(
        val name: String,
        val opening: Boolean,
        val mentioned: List<UInt> = emptyList(),
        val watchers: List<UInt> = emptyList(),
        val teamMembers: List<UInt> = emptyList(),
        val watchersMentioned: Boolean = false,
        val everyoneMentioned: Boolean = false,
        val expected: List<Pair<UInt, NoticeReason>>,
    )

    private val groupMessageCases = listOf(
        GroupMessageCase(
            "a reply naming a team tells its members in the room, then the watchers",
            false, watchers = listOf(2u), teamMembers = listOf(3u, 4u),
            expected = listOf(3u to TEAM_MENTIONED, 4u to TEAM_MENTIONED, 2u to THREAD_REPLIED),
        ),
        GroupMessageCase(
            "a reply naming the watchers tells the watchers but not the author",
            false, watchers = listOf(1u, 2u, 3u), watchersMentioned = true,
            expected = listOf(2u to WATCHERS_MENTIONED, 3u to WATCHERS_MENTIONED),
        ),
        GroupMessageCase(
            "naming the watchers in an OPENING message reaches nobody beyond the room's base notice",
            true, watchers = listOf(1u, 2u), watchersMentioned = true,
            expected = listOf(2u to THREAD_CREATED, 3u to THREAD_CREATED, 4u to THREAD_CREATED, 5u to THREAD_CREATED, 6u to THREAD_CREATED),
        ),
        GroupMessageCase(
            "naming everyone in a reply tells the whole room, a watcher included, and not the author",
            false, watchers = listOf(2u), everyoneMentioned = true,
            expected = listOf(2u to EVERYONE_MENTIONED, 3u to EVERYONE_MENTIONED, 4u to EVERYONE_MENTIONED, 5u to EVERYONE_MENTIONED,
                6u to EVERYONE_MENTIONED),
        ),
        GroupMessageCase(
            "naming everyone in an opening message tells every member, not 'started a thread'",
            true, everyoneMentioned = true,
            expected = listOf(2u to EVERYONE_MENTIONED, 3u to EVERYONE_MENTIONED, 4u to EVERYONE_MENTIONED, 5u to EVERYONE_MENTIONED,
                6u to EVERYONE_MENTIONED),
        ),
        GroupMessageCase(
            "every reason at once: each person once, the most specific, in priority order",
            false, mentioned = listOf(5u), watchers = listOf(3u, 4u), teamMembers = listOf(5u, 3u),
            watchersMentioned = true, everyoneMentioned = true,
            expected = listOf(5u to MENTIONED, 3u to TEAM_MENTIONED, 4u to WATCHERS_MENTIONED, 2u to EVERYONE_MENTIONED,
                6u to EVERYONE_MENTIONED),
        ),
        GroupMessageCase(
            "the author is never told, whichever group names them",
            false, watchers = listOf(1u), teamMembers = listOf(1u, 2u), watchersMentioned = true, everyoneMentioned = true,
            expected = listOf(2u to TEAM_MENTIONED, 3u to EVERYONE_MENTIONED, 4u to EVERYONE_MENTIONED, 5u to EVERYONE_MENTIONED,
                6u to EVERYONE_MENTIONED),
        ),
        GroupMessageCase("a team nobody in the room is on tells nobody", false, expected = emptyList()),
    )

    @Test
    fun `group mentions, case by case`() {
        for (case in groupMessageCases) {
            val audience = NoticeAudience.ofMessage(
                case.opening, actor, case.mentioned, case.watchers, members,
                case.teamMembers, case.watchersMentioned, case.everyoneMentioned,
            )
            assertEquals(case.expected, told(audience), case.name)
            assertEquals(audience.size, audience.map { it.userId }.toSet().size, "${case.name}: one notice per person")
        }
    }

    @Test
    fun `a person named by several groups falls back through them in priority order`() {
        val audience = NoticeAudience.ofMessage(
            false, actor, emptyList(), listOf(3u), members, teamMentioned = listOf(3u), watchersMentioned = true, everyoneMentioned = true,
        )
        val three = audience.single { it.userId == 3u }
        assertEquals(TEAM_MENTIONED, three.reason)
        assertEquals(listOf(WATCHERS_MENTIONED, EVERYONE_MENTIONED, THREAD_REPLIED), three.fallbacks)
    }

    private class GroupEditCase(
        val name: String,
        val opening: Boolean,
        val existing: Map<UInt, NoticeReason>,
        /** The most specific mention reason each person had from the OLD body, as of the edit (1.1 A39). */
        val before: Map<UInt, NoticeReason>,
        val watchers: List<UInt> = emptyList(),
        val teamMembers: List<UInt> = emptyList(),
        val watchersMentioned: Boolean = false,
        val everyoneMentioned: Boolean = false,
        val mentioned: List<UInt> = emptyList(),
        val add: List<Pair<UInt, NoticeReason>> = emptyList(),
        val relabel: List<Pair<UInt, NoticeReason>> = emptyList(),
        val remove: List<UInt> = emptyList(),
    )

    private val everyoneElse = listOf(2u, 3u, 4u, 5u, 6u)

    private val groupEditCases = listOf(
        GroupEditCase(
            "a reply adds everyone: the watcher's reply notice is upgraded (delete and insert), the rest are added",
            false, mapOf(2u to THREAD_REPLIED), before = emptyMap(), watchers = listOf(2u), everyoneMentioned = true,
            add = everyoneElse.map { it to EVERYONE_MENTIONED }, remove = listOf(2u),
        ),
        GroupEditCase(
            "a reply drops everyone: a watcher falls back to replied in place, anybody else loses the notice",
            false, mapOf(2u to EVERYONE_MENTIONED, 3u to EVERYONE_MENTIONED), before = emptyMap(), watchers = listOf(2u),
            relabel = listOf(2u to THREAD_REPLIED), remove = listOf(3u),
        ),
        GroupEditCase(
            "the opening message drops everyone: every member falls back to 'started a thread' in place",
            true, everyoneElse.associateWith { EVERYONE_MENTIONED }, before = emptyMap(),
            relabel = everyoneElse.map { it to THREAD_CREATED },
        ),
        GroupEditCase(
            "the opening message adds everyone: each member's 'started a thread' becomes a fresh notice",
            true, everyoneElse.associateWith { THREAD_CREATED }, before = emptyMap(), everyoneMentioned = true,
            add = everyoneElse.map { it to EVERYONE_MENTIONED }, remove = everyoneElse,
        ),
        GroupEditCase(
            "a KEPT everyone re-addresses nobody, though a member who joined since is in the audience",
            false, emptyMap(), before = everyoneElse.associateWith { EVERYONE_MENTIONED }, everyoneMentioned = true,
        ),
        GroupEditCase(
            "a kept everyone and a direct mention of someone it addressed less specifically: newly mentioned, a fresh notice",
            false, mapOf(3u to EVERYONE_MENTIONED), before = everyoneElse.associateWith { EVERYONE_MENTIONED }, everyoneMentioned = true,
            mentioned = listOf(3u), add = listOf(3u to MENTIONED), remove = listOf(3u),
        ),
        GroupEditCase(
            "a kept team and a direct mention of someone it addressed at the same or a more specific reason: nothing",
            false, mapOf(3u to MENTIONED), before = mapOf(3u to MENTIONED), teamMembers = listOf(3u), mentioned = listOf(3u),
        ),
        GroupEditCase(
            "everyone swapped for a team on an opening message: the team is told afresh, the rest fall back in place",
            true, everyoneElse.associateWith { EVERYONE_MENTIONED }, before = everyoneElse.associateWith { EVERYONE_MENTIONED },
            teamMembers = listOf(2u), add = listOf(2u to TEAM_MENTIONED), remove = listOf(2u),
            relabel = listOf(3u, 4u, 5u, 6u).map { it to THREAD_CREATED },
        ),
        GroupEditCase(
            "the direct mention taken out while everyone is kept: the mention notice falls to everyone in place",
            false, mapOf(2u to MENTIONED), before = mapOf(2u to MENTIONED) + listOf(3u, 4u, 5u, 6u).associateWith { EVERYONE_MENTIONED },
            everyoneMentioned = true,
            relabel = listOf(2u to EVERYONE_MENTIONED),
        ),
        GroupEditCase(
            "a group kind the body no longer earns for a person who was addressed more specifically before: relabelled in place",
            false, mapOf(2u to EVERYONE_MENTIONED), before = mapOf(2u to TEAM_MENTIONED), teamMembers = listOf(2u),
            relabel = listOf(2u to TEAM_MENTIONED),
        ),
        GroupEditCase(
            "a team swapped for another: the new team's members are added, the old team's alone lose theirs, the shared keep",
            false, mapOf(2u to TEAM_MENTIONED, 3u to TEAM_MENTIONED),
            before = mapOf(2u to TEAM_MENTIONED, 3u to TEAM_MENTIONED), teamMembers = listOf(3u, 4u),
            add = listOf(4u to TEAM_MENTIONED), remove = listOf(2u),
        ),
        GroupEditCase(
            "a team replaced by everyone: its members are relabelled in place, the others added",
            false, mapOf(2u to TEAM_MENTIONED), before = mapOf(2u to TEAM_MENTIONED), everyoneMentioned = true,
            add = listOf(3u, 4u, 5u, 6u).map { it to EVERYONE_MENTIONED }, relabel = listOf(2u to EVERYONE_MENTIONED),
        ),
        GroupEditCase(
            "a reply adds the watchers: a watcher's reply notice is upgraded, another watcher gets one",
            false, mapOf(2u to THREAD_REPLIED), before = emptyMap(), watchers = listOf(2u, 3u), watchersMentioned = true,
            add = listOf(2u to WATCHERS_MENTIONED, 3u to WATCHERS_MENTIONED), remove = listOf(2u),
        ),
        GroupEditCase(
            "the opening message adds the watchers: nobody beyond its author watches, nothing changes",
            true, everyoneElse.associateWith { THREAD_CREATED }, before = emptyMap(), watchers = listOf(1u), watchersMentioned = true,
        ),
        GroupEditCase(
            "a mention notice held through a group the edit removed, with a direct mention added for someone else",
            false, mapOf(4u to WATCHERS_MENTIONED), before = mapOf(4u to WATCHERS_MENTIONED), mentioned = listOf(6u), watchers = listOf(4u),
            add = listOf(6u to MENTIONED), relabel = listOf(4u to THREAD_REPLIED),
        ),
    )

    @Test
    fun `an edit that adds, drops or keeps a group mention, case by case`() {
        for (case in groupEditCases) {
            val revision = NoticeAudience.ofEdit(
                case.opening, actor, case.existing, case.mentioned, case.before, case.watchers, members,
                MentionGroupAudience(case.teamMembers, case.watchersMentioned, case.everyoneMentioned),
            )
            assertEquals(case.add, told(revision.add), "${case.name}: add")
            assertEquals(case.relabel, told(revision.relabel), "${case.name}: relabel")
            assertEquals(case.remove.toSet(), revision.remove.toSet(), "${case.name}: remove")
            val kept = case.existing.keys - revision.remove.toSet()
            assertTrue(revision.add.none { it.userId in kept }, "${case.name}: nobody who keeps a notice is counted again")
        }
    }

    // The author of a quoted message (1.3) is user 3 in every case below; user 1 is the author of the quoting message.
    private val quotedAuthor = 3u

    @Test
    fun `a quote outranks every group and base reason and yields only to a direct mention`() {
        fun one(notices: List<Notice>, user: UInt) = notices.single { it.userId == user }
        val everything = NoticeAudience.ofMessage(
            false, actor, emptyList(), listOf(3u), members, teamMentioned = listOf(3u), watchersMentioned = true, everyoneMentioned = true,
            quoted = quotedAuthor,
        )
        val three = one(everything, quotedAuthor)
        assertEquals(MESSAGE_QUOTED, three.reason)
        assertEquals(listOf(TEAM_MENTIONED, WATCHERS_MENTIONED, EVERYONE_MENTIONED, THREAD_REPLIED), three.fallbacks)
        assertEquals(everything.map { it.userId }.toSet().size, everything.size, "one notice per person")

        val mentioned = one(NoticeAudience.ofMessage(false, actor, listOf(quotedAuthor), listOf(3u), members, quoted = quotedAuthor), 3u)
        assertEquals(MENTIONED, mentioned.reason, "a direct mention wins over the quote")
        assertEquals(listOf(MESSAGE_QUOTED, THREAD_REPLIED), mentioned.fallbacks)

        assertEquals(
            listOf(quotedAuthor to MESSAGE_QUOTED),
            told(NoticeAudience.ofMessage(false, actor, emptyList(), emptyList(), members, quoted = quotedAuthor)),
            "a quote tells a non-watcher who is not mentioned",
        )
        assertEquals(
            emptyList(),
            told(NoticeAudience.ofMessage(false, actor, emptyList(), emptyList(), members, quoted = actor)),
            "quoting oneself tells nobody",
        )
    }

    private class QuoteEditCase(
        val name: String,
        val existing: Map<UInt, NoticeReason>,
        val mentioned: List<UInt> = emptyList(),
        /** The most specific mention reason each person had from the OLD body. */
        val before: Map<UInt, NoticeReason> = emptyMap(),
        val watchers: List<UInt> = emptyList(),
        val everyoneMentioned: Boolean = false,
        val add: List<Pair<UInt, NoticeReason>> = emptyList(),
        val relabel: List<Pair<UInt, NoticeReason>> = emptyList(),
        val remove: List<UInt> = emptyList(),
    )

    private val quoteEditCases = listOf(
        QuoteEditCase(
            "holds the quote notice, the edit adds a direct mention: a fresh MENTIONED replaces it",
            mapOf(3u to MESSAGE_QUOTED), mentioned = listOf(3u), add = listOf(3u to MENTIONED), remove = listOf(3u),
        ),
        QuoteEditCase(
            "holds MENTIONED, the edit drops the token: relabelled to the quote in place, never deleted",
            mapOf(3u to MENTIONED), before = mapOf(3u to MENTIONED), relabel = listOf(3u to MESSAGE_QUOTED),
        ),
        QuoteEditCase(
            "holds the quote notice, the edit adds @everyone: nothing for them, the others are told",
            mapOf(3u to MESSAGE_QUOTED), everyoneMentioned = true, add = listOf(2u, 4u, 5u, 6u).map { it to EVERYONE_MENTIONED },
        ),
        QuoteEditCase(
            "holds a reply notice (quotes switched off), the edit adds @everyone: told afresh like any member",
            mapOf(3u to THREAD_REPLIED), watchers = listOf(3u), everyoneMentioned = true,
            add = listOf(3u to MESSAGE_QUOTED) + listOf(2u, 4u, 5u, 6u).map { it to EVERYONE_MENTIONED },
            remove = listOf(3u),
        ),
        QuoteEditCase(
            "holds nothing (quotes switched off), the edit adds @everyone: told like any member",
            emptyMap(), everyoneMentioned = true,
            add = listOf(3u to MESSAGE_QUOTED) + listOf(2u, 4u, 5u, 6u).map { it to EVERYONE_MENTIONED },
        ),
        QuoteEditCase(
            "holds nothing, the edit addresses them no more than before: nothing (a base notice is never added)",
            emptyMap(), mentioned = listOf(2u), add = listOf(2u to MENTIONED),
        ),
        QuoteEditCase(
            "holds nothing, a KEPT everyone: nothing (the edit re-addresses nobody)",
            emptyMap(), everyoneMentioned = true, before = listOf(2u, 3u, 4u, 5u, 6u).associateWith { EVERYONE_MENTIONED },
        ),
        QuoteEditCase(
            "holds the quote notice, nothing about them changes: nothing",
            mapOf(3u to MESSAGE_QUOTED), watchers = listOf(3u),
        ),
    )

    @Test
    fun `an edit of a message that quotes, case by case`() {
        for (case in quoteEditCases) {
            val revision = NoticeAudience.ofEdit(
                false, actor, case.existing, case.mentioned, case.before, case.watchers, members,
                groups = MentionGroupAudience(everyone = case.everyoneMentioned), quoted = quotedAuthor,
            )
            assertEquals(case.add, told(revision.add), "${case.name}: add")
            assertEquals(case.relabel, told(revision.relabel), "${case.name}: relabel")
            assertEquals(case.remove.toSet(), revision.remove.toSet(), "${case.name}: remove")
            val kept = case.existing.keys - revision.remove.toSet()
            assertTrue(revision.add.none { it.userId in kept }, "${case.name}: nobody who keeps a notice is counted again")
        }
    }

    @Test
    fun `a quoted author told afresh by an edit keeps the quote and the group reason as fallbacks, in priority order`() {
        val revision = NoticeAudience.ofEdit(
            false, actor, emptyMap(), emptyList(), emptyMap(), listOf(quotedAuthor), members,
            groups = MentionGroupAudience(everyone = true), quoted = quotedAuthor,
        )
        val three = revision.add.single { it.userId == quotedAuthor }
        assertEquals(MESSAGE_QUOTED, three.reason)
        assertEquals(listOf(EVERYONE_MENTIONED, THREAD_REPLIED), three.fallbacks)
    }

    @Test
    fun `here reaches only its snapshotted people and sits between watchers and everyone`() {
        val notices = NoticeAudience.ofMessage(
            opening = false,
            authorId = actor,
            mentioned = emptyList(),
            watchers = listOf(2u, 3u),
            members = members,
            watchersMentioned = true,
            everyoneMentioned = true,
            hereMentioned = listOf(3u, 4u),
        ).associateBy { it.userId }
        assertEquals(WATCHERS_MENTIONED, notices.getValue(3u).reason)
        assertEquals(listOf(HERE_MENTIONED, EVERYONE_MENTIONED, THREAD_REPLIED), notices.getValue(3u).fallbacks)
        assertEquals(HERE_MENTIONED, notices.getValue(4u).reason)
        assertEquals(EVERYONE_MENTIONED, notices.getValue(5u).reason)
    }
}
