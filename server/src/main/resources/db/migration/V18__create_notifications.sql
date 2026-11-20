-- Notices (0.6.0): `notifications` holds one row per thing a member should look at, today a mention of them in a message and the hand-over
-- of a thread to them. A row is a POINTER, ids only: no name, title or body is stored, so none can leak after a leave or a moderator's
-- delete, none needs scrubbing, and a renamed room, thread or person shows its current name (read at request time).
--
-- Delete class (persistence.md): HARD delete. A notice is a pointer, not a record: it goes with what it points at (its message, its
-- thread, its room, a user: ON DELETE CASCADE; messages and users are only soft-deleted, so the message's own delete removes its notices
-- by hand and a test purge cascades), with the MEMBERSHIP it was made for (`fk_notifications_member`: removing a member deletes that
-- person's notices of the room, and a person who rejoins starts clean) and by retention (a sweep deletes rows older than
-- `notifications.retentionDays`).
--
-- `notification_type` carries NO CHECK: the Kotlin enum is the whitelist and its names are persisted and frozen (the V5 rule); a stored
-- name an older or newer build does not know is invisible to it, never fatal.
-- `message_id` is null for a hand-over (a thread-level notice). The unique index makes the insert of a mention idempotent (`ON CONFLICT
-- DO NOTHING`): one notice per (message, recipient, kind). A hand-over has no message, so every hand-over is its own notice.
-- `seen_at` is null while unseen. The two recipient indexes serve the drawer (newest first) and the badge (unseen only);
-- `idx_notifications_created` serves the retention purge, which reads the oldest rows in batches straight off it.
CREATE TABLE notifications (
    id                SERIAL      PRIMARY KEY,
    recipient_id      BIGINT      NOT NULL,
    room_id           INTEGER     NOT NULL,
    notification_type VARCHAR(40) NOT NULL,
    actor_id          BIGINT      NOT NULL REFERENCES users(id)    ON DELETE CASCADE,
    thread_id         INTEGER     NOT NULL REFERENCES threads(id)  ON DELETE CASCADE,
    message_id        INTEGER              REFERENCES messages(id) ON DELETE CASCADE,
    created_at        BIGINT      NOT NULL,
    seen_at           BIGINT,
    CONSTRAINT fk_notifications_member FOREIGN KEY (room_id, recipient_id)
        REFERENCES room_members (room_id, user_id) ON DELETE CASCADE
);
CREATE INDEX idx_notifications_recipient ON notifications (recipient_id, id);
CREATE INDEX idx_notifications_unseen ON notifications (recipient_id, id) WHERE seen_at IS NULL;
CREATE UNIQUE INDEX uq_notifications_message ON notifications (message_id, recipient_id, notification_type)
    WHERE message_id IS NOT NULL;
CREATE INDEX idx_notifications_created ON notifications (created_at);
