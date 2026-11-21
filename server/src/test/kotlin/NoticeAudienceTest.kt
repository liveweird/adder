package ch.nokillswit

import ch.nokillswit.threads.Notice
import ch.nokillswit.threads.NoticeAudience
import ch.nokillswit.threads.NoticeReason
import ch.nokillswit.threads.NoticeReason.MENTIONED
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
            val revision = NoticeAudience.ofEdit(case.opening, actor, case.existing, case.mentioned, case.added, case.watchers, members)
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
        val revision = NoticeAudience.ofEdit(false, actor, emptyMap(), listOf(1u, 2u), listOf(1u, 2u), listOf(1u), members)
        assertEquals(listOf(2u to MENTIONED), told(revision.add))
    }
}
