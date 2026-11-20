-- Read state (0.6.0): what a member has looked at. View state, a member's own: it is never audited, never announced and never read by
-- anybody but its owner.
--
-- `room_members.seen_at` is when the member last looked at the room (the rail's "new activity" dot compares the room's
-- `last_activity_at` with it); `room_members.read_from` is the floor of unread counting (nothing before it is unread; a later "mark all
-- as read" would raise it). Both default to 0, which means "since joining": every read uses GREATEST(column, added_at), so a member row
-- written by any path (the room creator, an add, a team snapshot, a raw test helper) is right without naming them.
--
-- The statement below is the upgrade baseline: once this migration has run, nothing that was already written is unread or new for
-- anybody. Each member row is stamped ONCE with its room's own `last_activity_at`, an instant of the application's clock: every message
-- and every activity written so far is at or before it (a write stamps its message and the room with the same instant), so everything
-- that existed is read, and the first message written after the upgrade is strictly newer. No database clock is read.
ALTER TABLE room_members ADD COLUMN seen_at   BIGINT NOT NULL DEFAULT 0;
ALTER TABLE room_members ADD COLUMN read_from BIGINT NOT NULL DEFAULT 0;
UPDATE room_members m SET seen_at = r.last_activity_at, read_from = r.last_activity_at
FROM rooms r WHERE r.id = m.room_id;

-- One member's marker in one thread: the id of the last message they have read, only ever moving forward (the write is a conditional
-- upsert). Delete class (persistence.md): HARD delete - view state, overwritten in place; the marker goes with its thread and with the
-- membership. `last_read_message_id` is a POSITION, not a reference: no foreign key to `messages`, so a deleted message needs no cleanup.
-- The membership key refuses a marker for a non-member (23503) and takes a leaver's markers with the row, so rejoining starts clean.
CREATE TABLE thread_reads (
    user_id              BIGINT  NOT NULL,
    thread_id            INTEGER NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
    room_id              INTEGER NOT NULL,
    last_read_message_id INTEGER NOT NULL CHECK (last_read_message_id > 0),
    read_at              BIGINT  NOT NULL,
    PRIMARY KEY (user_id, thread_id),
    CONSTRAINT fk_thread_reads_member FOREIGN KEY (room_id, user_id)
        REFERENCES room_members (room_id, user_id) ON DELETE CASCADE
);

-- The read marker marks the person's unseen notices of ONE thread seen in the same transaction (`NotificationService.threadRead`). V18's
-- partial index leads with the recipient and the id, which would walk all of that person's unseen notices to find one thread's; this one
-- leads with the recipient and the thread, and is partial like it, so it holds only the rows a read can still change.
CREATE INDEX idx_notifications_unseen_thread ON notifications (recipient_id, thread_id) WHERE seen_at IS NULL;
