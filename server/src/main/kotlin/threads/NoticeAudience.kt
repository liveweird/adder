package ch.nokillswit.threads

/**
 * Who is told about an event, and why (`chat-model.md` "Watching, group mentions and notice kinds (1.1)", the rules of 1.1 D3, D4, D6): the
 * PURE kernel of the audience, with no database and no clock, so its table is a unit test (`NoticeAudienceTest`). The caller (the mention
 * follower of a message write, the status service) reads the facts INSIDE the write under the room lock and hands them in:
 *
 *  - `members`: the room's ACTIVE members now;
 *  - `watchers`: the thread's watchers who are ALSO active members (a raw read of the watches is never an audience: a deleted account's
 *    or a left member's watch is inert);
 *  - `mentioned`: the members the body names directly (`<@id>`), in order of appearance.
 *
 * Every function answers a list in PRIORITY order, the most specific reason first (the order of [NoticeReason]), each person ONCE with
 * their most specific reason and never the actor: the writer's email budget admits people in this order, so a message that names someone
 * and also the whole room spends the budget on the named first. A [Notice] also carries the person's OTHER applicable reasons
 * ([Notice.fallbacks], in priority order): the writer falls back to the next one when the top kind is switched off on both channels
 * (1.1 A36). The kernel decides who and why; whether and how each person is reached (their switches, the budgets) is the writer's.
 *
 * What makes NO notice is also here, by absence: a title or field edit, a message edit as such, a delete, a reaction, a pin and the
 * workflow PUT's `moveThreads` have no function.
 */
internal object NoticeAudience {
    /** The reasons that name a person for what the message SAYS (a mention); the base reasons below are for what the thread IS. */
    private val MENTION_REASONS = setOf(
        NoticeReason.MENTIONED,
        NoticeReason.TEAM_MENTIONED,
        NoticeReason.WATCHERS_MENTIONED,
        NoticeReason.EVERYONE_MENTIONED,
    )

    /** The most specific reason of each person (the rest as its fallbacks), in priority order, the [actorId] left out. */
    private fun resolved(actorId: UInt, candidates: List<Notice>): List<Notice> =
        candidates.filter { it.userId != actorId }.sortedBy { it.reason.ordinal }.groupBy { it.userId }.map { (user, reasons) ->
            Notice(user, reasons.first().reason, reasons.drop(1).map { it.reason }.distinct())
        }

    /**
     * A post or an OPENING message by [authorId]: a direct mention is `MENTIONED`; otherwise every active member is told `THREAD_CREATED`
     * of an opening message (1.1 D4), and the thread's watchers `THREAD_REPLIED` of a reply (1.1 D3). A reply tells no non-watcher.
     */
    fun ofMessage(
        opening: Boolean,
        authorId: UInt,
        mentioned: Collection<UInt>,
        watchers: Collection<UInt>,
        members: Collection<UInt>,
    ): List<Notice> {
        val direct = mentioned.map { Notice(it, NoticeReason.MENTIONED) }
        val base = if (opening) {
            members.map { Notice(it, NoticeReason.THREAD_CREATED) }
        } else {
            watchers.map { Notice(it, NoticeReason.THREAD_REPLIED) }
        }
        return resolved(authorId, direct + base)
    }

    /** A status move by [actorId]: the watchers (the actor never), the reason by the move. */
    fun ofStatusMove(transition: StatusTransition, actorId: UInt, watchers: Collection<UInt>): List<Notice> {
        val reason = when (transition) {
            StatusTransition.MOVED -> NoticeReason.THREAD_STATUS_CHANGED
            StatusTransition.CLOSED -> NoticeReason.THREAD_CLOSED
            StatusTransition.REOPENED -> NoticeReason.THREAD_REOPENED
        }
        return resolved(actorId, watchers.map { Notice(it, reason) })
    }

    /**
     * An owner hand-over by [actorId] to [newOwnerId]: the new owner is told `THREAD_ASSIGNED` (not when they took the thread
     * themself), every other watcher `THREAD_OWNER_CHANGED`. [watchers] already holds the new owner (the hand-over adds the watch
     * first), but the new owner is named here regardless, so a missing watch never silences the person who was given the thread.
     */
    fun ofHandOver(actorId: UInt, newOwnerId: UInt, watchers: Collection<UInt>): List<Notice> =
        resolved(
            actorId,
            listOf(Notice(newOwnerId, NoticeReason.THREAD_ASSIGNED)) + watchers.map { Notice(it, NoticeReason.THREAD_OWNER_CHANGED) },
        )

    /**
     * An EDIT of a message by [authorId] whose notices are [existing] (by recipient): what the new body addresses ([ofMessage] on the
     * new facts, [mentioned] being everybody it mentions NOW) against what they already hold, the table of `chat-model.md` "Watching, group
     * mentions and notice kinds (1.1)". Only the NEWLY addressed are told ([added]: addressed by the new body and not by the old one, 1.1
     * A34; the group tokens of the next step widen the word, not the rule): a mention the edit kept, whose notice is gone (retention, a
     * switch, a leave and rejoin) or was never made, stays without one.
     *
     *  - nothing yet, newly MENTIONED: add. (A base notice is never added by an edit: an edit as such makes no notice.)
     *  - a LESS specific notice, newly addressed more specifically: delete and add, so the person has a fresh, unseen notice
     *    (the person is in both [NoticeRevision.remove] and [NoticeRevision.add]);
     *  - a mention notice, now addressed less specifically: relabelled in place;
     *  - a mention notice, now not addressed at all: deleted;
     *  - a base notice that is no longer earned, or any notice of the same reason: kept.
     *
     * Nobody who already holds a notice that stays is in [NoticeRevision.add], so the email budget never counts them twice.
     */
    fun ofEdit(
        opening: Boolean,
        authorId: UInt,
        existing: Map<UInt, NoticeReason>,
        mentioned: Collection<UInt>,
        added: Collection<UInt>,
        watchers: Collection<UInt>,
        members: Collection<UInt>,
    ): NoticeRevision {
        val now = ofMessage(opening, authorId, mentioned, watchers, members).associateBy { it.userId }
        val newly = added.toSet()
        val add = mutableListOf<Notice>()
        val relabel = mutableListOf<Notice>()
        val remove = mutableListOf<UInt>()
        for ((user, notice) in now) {
            val held = existing[user]
            val target = notice.reason
            when {
                held == null -> if (target in MENTION_REASONS && user in newly) add += notice
                held == target -> Unit
                target.ordinal < held.ordinal -> if (user in newly) {
                    remove += user
                    add += notice
                }
                held in MENTION_REASONS -> relabel += notice
            }
        }
        for ((user, held) in existing) {
            if (user !in now && held in MENTION_REASONS) remove += user
        }
        return NoticeRevision(add = add, relabel = relabel, remove = remove)
    }
}
