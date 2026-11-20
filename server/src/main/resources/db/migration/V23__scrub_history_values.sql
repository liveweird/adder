-- 1.0.0: a thread's history no longer keeps raw text (the user's decision of 2026-11-20, checkup C-SEC-5; `chat-model.md` "History"). It
-- is append-only, so a credential pasted into a title or a TEXT or URL field could never be taken back. New events are written without
-- these values (`threads/ThreadFieldEvents.kt`, `ThreadEventService.kt`); this migration removes them from the rows already written.
-- Only `params` changes: the event, its type, its actor and its instant stay, so the history still says THAT a title or a field changed.
--
--   * TITLE_CHANGED {from, to}: both removed (the params become {}). The new title is no safer than the old: it is the next rename's
--     `from`, and the thread already shows the current title.
--   * CREATED {title, status, statusLabel}: the opening title is removed, the status stays.
--   * FIELD_CHANGED {field, fieldLabel, from?, to?}: `from` and `to` are removed UNLESS the room's field is a SELECT, MULTI_SELECT, USER,
--     DATE or NUMBER one (an option's label, a member's name, a date or a number: no free text). So a TEXT or URL field is scrubbed, and so
--     is an event whose field cannot be found in the room (an allow-list: when in doubt, scrub). A field's type is immutable per key
--     and a retired field keeps its row, so the type found now is the type at the time of the event.
--
-- Each statement is idempotent (it only selects rows that still hold a value), so a second run finds nothing, and one may be re-run by
-- hand after a rolling deploy, in which an older instance can still write the old shape until it stops (`persistence.md` "History
-- scrub (V23)"). `params` is JSON TEXT written only by `encodeParams`, so the casts to jsonb cannot fail: a control character, the one
-- thing jsonb refuses (U+0000), is refused by the input validation of titles and fields. The result is jsonb's rendering of the same
-- object (key order and a space after each colon differ), which `decodeParams` reads alike.
--
-- Locks and cost: the statements are plain UPDATEs, so they take ROW EXCLUSIVE on `thread_events` (readers and the appends of other
-- instances go on) and a row lock only on the rows they rewrite, which nothing else ever updates (the table is append-only). Each is one
-- pass over the table; the rows rewritten are the title and text-field events only. The old row versions stay in the heap until vacuum
-- (physical removal, `VACUUM FULL thread_events`, is for the operator: `persistence.md`), and in backups and WAL taken before.
UPDATE thread_events
SET params = '{}'
WHERE event_type = 'TITLE_CHANGED' AND params <> '{}';

UPDATE thread_events
SET params = (params::jsonb - 'title')::text
WHERE event_type = 'CREATED' AND (params::jsonb ->> 'title') IS NOT NULL;

UPDATE thread_events e
SET params = (e.params::jsonb - 'from' - 'to')::text
WHERE e.event_type = 'FIELD_CHANGED'
  AND ((e.params::jsonb ->> 'from') IS NOT NULL OR (e.params::jsonb ->> 'to') IS NOT NULL)
  AND NOT EXISTS (
      SELECT 1
      FROM threads t
      JOIN room_fields f ON f.room_id = t.room_id
      WHERE t.id = e.thread_id
        AND f."key" = (e.params::jsonb ->> 'field')
        AND f."type" IN ('SELECT', 'MULTI_SELECT', 'USER', 'DATE', 'NUMBER')
  );
