package ch.nokillswit.notifications

/*
 * The email digest's three statements (`persistence.md` "Notices"), split out of `NotificationDigest.kt` for its size cap; the constant
 * text is unchanged. `NotificationDigest` is the only caller.
 */

/**
 * Whether the recipient of notice `c` has the kind's EMAIL switch on (V28, 1.1 D7): the person's stored row says (`enabled`: an opt-in is
 * true, V22's "switched off" row false), and with no row the kind's DEFAULT does. The defaults live in code, so the kinds that are OFF for
 * email by default arrive as ONE array parameter (`NotificationType.defaultOn(EMAIL)` false): a kind in it with no row of the person's is
 * off. One parameter.
 */
private const val EMAIL_ON = """COALESCE(
        (SELECT p.enabled FROM user_notification_preferences p
         WHERE p.user_id = c.recipient_id AND p.channel = 'EMAIL' AND p.notification_type = c.notification_type),
        c.notification_type <> ALL(CAST(? AS varchar[])))"""

/**
 * The notices a digest may mail, one fragment for the claim (alias `c`, four parameters: the delay cutoff, the day floor, the people
 * inside the interval, the kinds off for email by default): unseen, unclaimed, older than the delay, younger than a day (nothing older is
 * ever mailed), of an ACTIVE account, of a kind whose email switch is ON for the person ([EMAIL_ON]: an opt-in, since every kind is off by
 * default), and not of a person already mailed within the interval (their notices stay unclaimed and ride one later email). A notice
 * that shows only by email (`in_app = false`) is due like any other.
 */
private const val DUE = """c.seen_at IS NULL AND c.emailed_at IS NULL
      AND c.created_at <= CAST(? AS int8) AND c.created_at > CAST(? AS int8)
      AND c.recipient_id <> ALL(CAST(? AS int8[]))
      AND EXISTS (SELECT 1 FROM users u WHERE u.id = c.recipient_id AND u.marked_as_deleted = false)
      AND $EMAIL_ON"""

/**
 * The claim (`persistence.md` "Notices"): ONE statement, constant text. The [DUE] notices, the oldest `?` of them ([DIGEST_SCAN_CAP]),
 * are grouped by person and the people taken in the order of their OLDEST waiting notice (at most `?` people: a flooded backlog is one
 * person's slot, never everybody's); ALL of a taken person's notices in the scan are locked `FOR UPDATE SKIP LOCKED` (a row another
 * process has claimed, or is claiming, is skipped, never waited for) and stamped `emailed_at` in the same statement, so a flooded
 * person's backlog is one digest and not a mail per tick: a notice is claimed, hence mailed, at most once. Each row comes back with its
 * rank among the person's notices, oldest first (the digest lists the first [DIGEST_MAX_NOTICES_PER_RECIPIENT] and counts the rest).
 * `MATERIALIZED` keeps each step to one evaluation. The scan is bounded by the cap, not by the people limit. Every parameter is cast.
 */
internal const val CLAIM = """
WITH eligible AS MATERIALIZED (
    SELECT c.id, c.recipient_id, c.created_at FROM notifications c WHERE $DUE
    ORDER BY c.created_at, c.id LIMIT CAST(? AS int4)),
people AS MATERIALIZED (
    SELECT recipient_id, MIN(created_at) AS oldest FROM eligible
    GROUP BY recipient_id ORDER BY oldest, recipient_id LIMIT CAST(? AS int4)),
ranked AS MATERIALIZED (
    SELECT e.id, row_number() OVER (PARTITION BY e.recipient_id ORDER BY e.created_at, e.id) AS rn
    FROM eligible e JOIN people ON people.recipient_id = e.recipient_id),
picked AS MATERIALIZED (
    SELECT n.id, r.rn FROM notifications n JOIN ranked r ON r.id = n.id
    WHERE n.seen_at IS NULL AND n.emailed_at IS NULL
    ORDER BY n.id FOR UPDATE OF n SKIP LOCKED)
UPDATE notifications n SET emailed_at = CAST(? AS int8)
FROM picked WHERE n.id = picked.id
RETURNING n.id, n.recipient_id, n.room_id, n.thread_id, n.message_id, n.notification_type, n.actor_id, picked.rn"""

/**
 * The settle, in the claim's transaction, before the claim: of the oldest `?` due notices ([DIGEST_SCAN_CAP], read once, so the
 * statement never walks more), the ones a digest can never mail (the kind's email switch off, or an account that is gone) are
 * stamped `emailed_at`, so they leave `idx_notifications_email_due` for good and never fill a scan again. A person who switches the
 * kind back on gets the notices made from then on. A never-mailable notice deeper than the cap waits until the head of the window has
 * drained. Parameters in order: the delay cutoff, the day floor, the scan cap, the kinds off for email by default, the stamp.
 */
internal const val SETTLE = """
WITH scanned AS MATERIALIZED (
    SELECT x.id, x.recipient_id, x.notification_type FROM notifications x
    WHERE x.seen_at IS NULL AND x.emailed_at IS NULL AND x.created_at <= CAST(? AS int8) AND x.created_at > CAST(? AS int8)
    ORDER BY x.created_at, x.id LIMIT CAST(? AS int4)),
doomed AS MATERIALIZED (
    SELECT n.id FROM notifications n
    WHERE n.id IN (
        SELECT c.id FROM scanned c
        WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.id = c.recipient_id AND u.marked_as_deleted = false)
           OR NOT $EMAIL_ON)
      AND n.seen_at IS NULL AND n.emailed_at IS NULL
    ORDER BY n.id FOR UPDATE SKIP LOCKED)
UPDATE notifications n SET emailed_at = CAST(? AS int8)
FROM doomed WHERE n.id = doomed.id
RETURNING n.id"""
