package ch.nokillswit.threads

/**
 * Who is told about an event, and why (`chat-model.md` "Watching, group mentions and notice kinds (1.1)", the rules of 1.1 D3, D4, D6): the
 * PURE kernel of the audience, with no database and no clock, so its table is a unit test (`NoticeAudienceTest`). The caller (the mention
 * follower of a message write, the status service) reads the facts INSIDE the write under the room lock and hands them in:
 *
 *  - `members`: the room's ACTIVE members now;
 *  - `watchers`: the thread's watchers who are ALSO active members (a raw read of the watches is never an audience: a deleted account's
 *    or a left member's watch is inert);
 *  - `mentioned`: the members the body names directly (`<@id>`), in order of appearance;
 *  - `teamMentioned`: the room's active members on the roster of a team the body names (`<@team:id>`), already intersected with the
 *    room (a team member who is not in the room is never told); `watchersMentioned` and `everyoneMentioned`: the body holds `<@watchers>`
 *    and `<@everyone>` (the people are then `watchers` and `members`);
 *  - `quoted`: the author of the message this one quotes (1.3), already an ACTIVE member of the room and not the actor: they are told
 *    `MESSAGE_QUOTED`, which outranks every group mention and the base reasons but not a direct mention.
 *
 * A group mention is `TEAM_MENTIONED`, `WATCHERS_MENTIONED` or `EVERYONE_MENTIONED` (not "direct": over the wide-event budget they are
 * the first to go). `<@watchers>` in an OPENING message reaches nobody (only the author watches); `<@everyone>` reaches every member.
 *
 * Every function answers a list in PRIORITY order, the most specific reason first (the order of [NoticeReason]), each person ONCE with
 * their most specific reason and never the actor: the writer's email budget admits people in this order, so a message that names someone
 * and also the whole room spends the budget on the named first. A [Notice] also carries the person's OTHER applicable reasons
 * ([Notice.fallbacks], in priority order): the writer falls back to the next one when the top kind is switched off on both channels
 * (1.1 A36). The kernel decides who and why; whether and how each person is reached (their switches, the budgets) is the writer's.
 *
 * Titles, field edits, deletes and pins generate no notices. A reaction targets the original author independently of this audience.
 */
internal data class MentionGroupAudience(
    val teams: Collection<UInt> = emptyList(),
    val watchers: Boolean = false,
    val everyone: Boolean = false,
    val here: Collection<UInt> = emptyList(),
)

internal object NoticeAudience {
    /**
     * The reasons that name a person for what the message SAYS (a mention); the base reasons below are for what the thread IS.
     * `MESSAGE_QUOTED` is in NEITHER set: a quote never changes, so an edit never adds or removes it by itself.
     */
    internal val MENTION_REASONS = setOf(
        NoticeReason.MENTIONED,
        NoticeReason.TEAM_MENTIONED,
        NoticeReason.WATCHERS_MENTIONED,
        NoticeReason.HERE_MENTIONED,
        NoticeReason.EVERYONE_MENTIONED,
    )

    private fun Notice.allApplicable(): List<NoticeReason> = listOf(reason) + fallbacks

    /** The most specific reason of each person (the rest as its fallbacks), in priority order, the [actorId] left out. */
    private fun resolved(actorId: UInt, candidates: List<Notice>): List<Notice> =
        candidates.filter { it.userId != actorId }.sortedBy { it.reason.ordinal }.groupBy { it.userId }.map { (user, reasons) ->
            Notice(user, reasons.first().reason, reasons.drop(1).map { it.reason }.distinct())
        }

    /**
     * A post or an OPENING message by [authorId]: a direct mention is `MENTIONED`, then the author of the [quoted] message
     * (`MESSAGE_QUOTED`), a team mention, the watchers mention and everyone (the order of [NoticeReason]); otherwise every active
     * member is told `THREAD_CREATED` of an opening message (1.1 D4), and the thread's watchers `THREAD_REPLIED` of a reply (1.1 D3).
     * A reply tells no non-watcher unless a mention names them. [members] is read only for an opening message and for `<@everyone>`;
     * [watchers] only for a reply (an opening message's watchers are the author alone).
     */
    fun ofMessage(
        opening: Boolean,
        authorId: UInt,
        mentioned: Collection<UInt>,
        watchers: Collection<UInt>,
        members: Collection<UInt>,
        teamMentioned: Collection<UInt> = emptyList(),
        watchersMentioned: Boolean = false,
        everyoneMentioned: Boolean = false,
        quoted: UInt? = null,
        hereMentioned: Collection<UInt> = emptyList(),
    ): List<Notice> {
        val direct = mentioned.map { Notice(it, NoticeReason.MENTIONED) }
        val quote = listOfNotNull(quoted).map { Notice(it, NoticeReason.MESSAGE_QUOTED) }
        val teams = teamMentioned.map { Notice(it, NoticeReason.TEAM_MENTIONED) }
        val watching = if (watchersMentioned && !opening) watchers.map { Notice(it, NoticeReason.WATCHERS_MENTIONED) } else emptyList()
        val here = hereMentioned.map { Notice(it, NoticeReason.HERE_MENTIONED) }
        val room = if (everyoneMentioned) members.map { Notice(it, NoticeReason.EVERYONE_MENTIONED) } else emptyList()
        val base = if (opening) {
            members.map { Notice(it, NoticeReason.THREAD_CREATED) }
        } else {
            watchers.map { Notice(it, NoticeReason.THREAD_REPLIED) }
        }
        return resolved(authorId, direct + quote + teams + watching + here + room + base)
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
     * new facts, [mentioned] being everybody it mentions NOW, the group arguments as there) against what they already hold, the table of
     * `chat-model.md` "Watching, group mentions and notice kinds (1.1)". Only the NEWLY addressed are told. [before] is the most specific
     * mention reason each person had from the OLD body (its direct rows and its groups, the caller reads their audience as of the edit,
     * 1.1 A34, A39): a person is newly addressed at a reason when they were not addressed at it, nor at anything more specific, before. So
     * a person the edit now names directly is always newly addressed (a group addressed them less specifically), a mention the edit kept
     * whose notice is gone (retention, a switch, a leave and rejoin) stays without one, and a KEPT group token re-addresses nobody.
     *
     *  - nothing yet, newly MENTIONED: add. (A base notice is never added by an edit: an edit as such makes no notice.)
     *  - a LESS specific notice, newly addressed more specifically: delete and add, so the person has a fresh, unseen notice
     *    (the person is in both [NoticeRevision.remove] and [NoticeRevision.add]);
     *  - a mention notice, now addressed less specifically, or a group kind the body no longer earns for the person: relabelled in place;
     *  - a mention notice, now not addressed at all: deleted;
     *  - a base notice that is no longer earned, or any notice of the same reason: kept.
     *
     * Nobody who already holds a notice that stays is in [NoticeRevision.add], so the email budget never counts them twice.
     *
     * The author of the [quoted] message (1.3) is one more person with a reason above the group mentions. "Newly addressed" is judged on
     * the person's most specific MENTION reason among ALL that apply to them (the top reason and its fallbacks), no longer on their top
     * reason: the two are the same for everybody who is not a quoted author (a mention always outranks a base reason), and a quote is
     * the first reason that can sit above a group mention without being one. So a quoted author who holds `MESSAGE_QUOTED` and is newly
     * named directly gets a fresh `MENTIONED`; one who holds `MENTIONED` and loses the token is relabelled to `MESSAGE_QUOTED`; one who
     * holds `MESSAGE_QUOTED` and is newly addressed by `@everyone` is told nothing; one who holds a base reason or nothing (quotes
     * switched off) and is newly addressed by `@everyone` is told like any member, with the first of their reasons that is switched on.
     */
    fun ofEdit(
        opening: Boolean,
        authorId: UInt,
        existing: Map<UInt, NoticeReason>,
        mentioned: Collection<UInt>,
        before: Map<UInt, NoticeReason>,
        watchers: Collection<UInt>,
        members: Collection<UInt>,
        groups: MentionGroupAudience = MentionGroupAudience(),
        quoted: UInt? = null,
    ): NoticeRevision {
        val now = ofMessage(
            opening, authorId, mentioned, watchers, members, groups.teams, groups.watchers, groups.everyone, quoted, groups.here,
        ).associateBy { it.userId }
        // Newly addressed: by the person's most specific MENTION reason among all that apply now, addressed at it and not at it, nor at
        // anything more specific, BEFORE (1.1 A34, A39). No mention reason applies: nothing is newly said to them.
        fun newly(notice: Notice): Boolean {
            val reason = notice.allApplicable().filter { it in MENTION_REASONS }.minByOrNull { it.ordinal } ?: return false
            return before[notice.userId]?.let { it.ordinal > reason.ordinal } ?: true
        }
        val add = mutableListOf<Notice>()
        val relabel = mutableListOf<Notice>()
        val remove = mutableListOf<UInt>()
        for ((user, notice) in now) {
            val held = existing[user]
            val target = notice.reason
            when {
                held == null -> if (newly(notice)) add += notice
                held == target -> Unit
                target.ordinal < held.ordinal -> when {
                    newly(notice) -> {
                        remove += user
                        add += notice
                    }
                    // Not newly, and the group kind the person holds is no longer earned by the body: relabelled in place.
                    held in MENTION_REASONS && held !in notice.allApplicable() -> relabel += notice
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
