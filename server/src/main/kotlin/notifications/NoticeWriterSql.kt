package ch.nokillswit.notifications

// The constant SQL of [NoticeWriter] (split out to keep the writer under the file-size cap): every statement has constant text and
// casts every parameter, so a 2-person and a 500-person event are the same statement (`persistence.md` "Notices (V18)", "V29").

/**
 * ONE statement for every recipient of an event, constant text: `unnest` of the recipients, their kinds and their two per-person flags,
 * `ON CONFLICT DO NOTHING` on `uq_notifications_message_recipient` (one notice per message and person), the notices really made come back.
 * Per row: [in_app] (`u.a`: false is a mail-only notice the bell never shows) and `emailed_at` (`u.s`: the digest must never mail it, so it
 * is stamped with the notice's own instant, V22's "settled, never mailed": the kind's email switch is off, or the actor is over the daily
 * budget). Every parameter is cast, so a null message id (a hand-over, a status move) has a type.
 */
internal const val INSERT_NOTICES = """
INSERT INTO notifications (recipient_id, room_id, notification_type, actor_id, thread_id, message_id, created_at, in_app, emailed_at)
SELECT u.r, CAST(? AS int4), u.t, CAST(? AS int8), CAST(? AS int4), CAST(? AS int4), CAST(? AS int8), u.a,
       CASE WHEN u.s THEN CAST(? AS int8) END
FROM unnest(CAST(? AS int8[]), CAST(? AS varchar[]), CAST(? AS bool[]), CAST(? AS bool[])) AS u(r, t, a, s)
ON CONFLICT DO NOTHING
RETURNING recipient_id, id, notification_type"""

/**
 * COALESCES a repeated thread-level notice (a status move, a hand-over: no message to make it unique, `THREAD_LEVEL_KINDS`): a recipient
 * who already holds an UNSEEN notice of the same kind about the same thread that can still DELIVER (it shows in the app, or its mail is
 * still to come: `in_app OR emailed_at IS NULL`) has it REPLACED by a new row, in ONE statement: the old row is deleted and the new one
 * inserted from the delete's `RETURNING`. A new id (not an update in place) because the list is ordered by id: the row tops the bell and
 * a `seen-all` that started earlier cannot swallow it. The new row takes the person's CURRENT in-app verdict (`u.a`), the actor and the
 * instant, and carries `emailed_at` over (a mailed one is not mailed again, a waiting one is not counted for the budget again).
 * A row still WAITING for the digest (`emailed_at IS NULL`) also carries its `created_at`: the digest is due `delay` after the FIRST
 * event, so a thread that churns is never postponed; and a waiting row already older than the delay (due now) is not coalesced into at all
 * (the cutoff argument): the event makes a fresh row. A dead row (not shown, mail settled) is left alone. The lookup is the person and the
 * thread, the two leading keys of the partial index `idx_notifications_unseen_thread` (V19: `seen_at IS NULL`). Arguments: the
 * recipients, their kinds, their in-app verdicts, the thread, the cutoff (now minus the delay), the actor, the thread, the instant; the
 * rows made come back.
 */
internal const val REFRESH_THREAD_NOTICES = """
WITH gone AS (
  DELETE FROM notifications n USING unnest(CAST(? AS int8[]), CAST(? AS varchar[]), CAST(? AS bool[])) AS u(r, t, a)
  WHERE n.recipient_id = u.r AND n.thread_id = CAST(? AS int4) AND n.message_id IS NULL AND n.seen_at IS NULL
    AND n.notification_type = u.t AND (n.in_app OR n.emailed_at IS NULL)
    AND (n.emailed_at IS NOT NULL OR n.created_at > CAST(? AS int8))
  RETURNING n.id, n.recipient_id, n.room_id, n.notification_type, n.emailed_at, n.created_at, u.a AS a),
kept AS (SELECT DISTINCT ON (recipient_id, notification_type) * FROM gone ORDER BY recipient_id, notification_type, id)
INSERT INTO notifications (recipient_id, room_id, notification_type, actor_id, thread_id, message_id, created_at, in_app, emailed_at)
SELECT k.recipient_id, k.room_id, k.notification_type, CAST(? AS int8), CAST(? AS int4), CAST(NULL AS int4),
       CASE WHEN k.emailed_at IS NULL THEN k.created_at ELSE CAST(? AS int8) END, k.a, k.emailed_at
FROM kept k
RETURNING recipient_id, notification_type, in_app"""

/** The rows an edit may relabel: what they are now, whether they show, whether their mail is still to come. Arguments: message, people. */
internal const val READ_RELABEL_ROWS = """
SELECT recipient_id, notification_type, in_app, emailed_at IS NULL FROM notifications
WHERE message_id = CAST(? AS int4) AND recipient_id = ANY(CAST(? AS int8[]))"""

/** ONE statement for every relabelled row of an edit. Arguments: the people, their new kinds and visibility, the message. */
internal const val RELABEL_ROWS = """
UPDATE notifications n SET notification_type = u.t, in_app = u.a
FROM unnest(CAST(? AS int8[]), CAST(? AS varchar[]), CAST(? AS bool[])) AS u(r, t, a)
WHERE n.message_id = CAST(? AS int4) AND n.recipient_id = u.r"""

/**
 * The unseen notices of one person in one thread up to a message become seen (the read marker's write, run without the room lock): the
 * person and the thread are the two leading keys of the partial index `idx_notifications_unseen_thread` (V19), so the statement touches
 * only that person's unseen rows of that thread however many notices they hold. A hand-over has no message and is seen with the thread.
 * Arguments: the instant, the person, the thread, the message.
 */
internal const val MARK_THREAD_SEEN_SQL = """
UPDATE notifications SET seen_at = CAST(? AS int8)
WHERE recipient_id = CAST(? AS int8) AND thread_id = CAST(? AS int4) AND seen_at IS NULL
  AND (message_id IS NULL OR message_id <= CAST(? AS int4))"""
