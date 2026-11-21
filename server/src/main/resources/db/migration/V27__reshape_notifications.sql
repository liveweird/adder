-- Notice kinds (1.1.0): a notice is made for MORE reasons than a mention and a hand-over (a reply in a watched thread, a new thread, a status
-- move, a group mention: `chat-model.md` "Notice kinds (V27, V28)"), so three things about the V18 table change.
--
-- 1. ONE notice per (message, recipient). V18's unique index included the kind, which let one message notify one person twice (a direct mention
--    AND "everyone"). The write now picks the most specific reason and inserts ONE row, `ON CONFLICT DO NOTHING` on this index. The defensive
--    DELETE keeps the lowest id of any pair that already exists: released builds made a `MENTIONED` per message and person only (a hand-over has
--    no message), so on a real database it matches nothing; it exists so the index can never fail to build on a database that was hand-edited.
-- 2. `in_app`. Channels are judged WHEN THE NOTICE IS MADE: a recipient who switched a kind's in-app switch off but its email on gets a row with
--    `in_app = false` (mailed by the digest, never listed, counted or shown by the bell). Both off: no row. The default TRUE is every row that
--    exists today, and a build that does not know the column still inserts (the default applies).
-- 3. The bell index. The badge and the drawer read `seen_at IS NULL AND in_app`, so the unseen index gains the predicate and replaces V18's.
--    And the list's `type` filter (any-of kinds, newest first) is served by `idx_notifications_recipient_type (recipient_id,
--    notification_type, id) WHERE in_app`: partial on `in_app` because EVERY list read (the page, its capped count, the badge, the
--    addressed count) filters `in_app`, so mail-only rows would only widen it.
--
-- Delete class: unchanged (HARD, `persistence.md` "Notices (V18)"). No data is rewritten beyond the defensive delete.
DELETE FROM notifications a USING notifications b
 WHERE a.message_id IS NOT NULL AND a.message_id = b.message_id AND a.recipient_id = b.recipient_id AND a.id > b.id;
DROP INDEX uq_notifications_message;
CREATE UNIQUE INDEX uq_notifications_message_recipient ON notifications (message_id, recipient_id) WHERE message_id IS NOT NULL;
ALTER TABLE notifications ADD COLUMN in_app BOOLEAN NOT NULL DEFAULT TRUE;
DROP INDEX idx_notifications_unseen;
CREATE INDEX idx_notifications_bell ON notifications (recipient_id, id) WHERE seen_at IS NULL AND in_app;
CREATE INDEX idx_notifications_recipient_type ON notifications (recipient_id, notification_type, id) WHERE in_app;
