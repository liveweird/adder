-- Watching (1.1.0): a member's own list of threads they want to hear about. A member's own state, like `thread_reads` (V19): toggled by
-- the member alone. It is read by the thread read (`watching`, `watcherCount`), the thread list (the row's `watching` and the `watching`
-- filter) and, in later steps, the notice kernel (replies, status moves and hand-overs of a watched thread notify its watchers).
--
-- Delete class (persistence.md): HARD delete - a join toggled on and off, no soft delete and no version column. A watch goes with its
-- thread (`ON DELETE CASCADE`) and with the membership: the composite key `(room_id, user_id)` references `room_members` and cascades, so a
-- leaver's or a removed member's watches go with the row, rejoining starts clean, and a watch for a non-member (23503) cannot exist. Every
-- writer therefore holds the person's membership row first (`RoomMemberService.holdMembership`, FOR KEY SHARE) so a removal waits and the
-- foreign key cannot fail under it. `user_id` has no foreign key to `users` of its own: a deleted user's watches are inert, like a
-- deleted user's membership row.
CREATE TABLE thread_watches (
    thread_id  INTEGER NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
    user_id    BIGINT  NOT NULL,
    room_id    INTEGER NOT NULL,
    created_at BIGINT  NOT NULL,
    PRIMARY KEY (thread_id, user_id),
    CONSTRAINT fk_thread_watches_member FOREIGN KEY (room_id, user_id)
        REFERENCES room_members (room_id, user_id) ON DELETE CASCADE
);

-- "The threads this person watches in this room" (the list's `watching` filter and the per-page `watching` read) leads with the person and
-- the room; the thread id last makes both an index-only probe. The same prefix serves the membership key's cascade (a member row's delete
-- finds that person's watches of that room by `room_id` and `user_id`). The primary key serves "who watches this thread".
CREATE INDEX idx_thread_watches_user_room ON thread_watches (user_id, room_id, thread_id);

-- Backfill: a thread's creator and its current owner, where they still hold a membership row, watch it from the upgrade on (otherwise the
-- owners of ongoing threads would hear nothing until they pressed Watch, unlike the owners of new threads). An owner who is also the
-- creator joins once (`IN (a, a)` matches one member row). The backfill creates no notice and is stamped with the thread's own creation
-- instant, an instant of the application's clock.
INSERT INTO thread_watches (thread_id, user_id, room_id, created_at)
SELECT t.id, m.user_id, t.room_id, t.created_at
FROM threads t
JOIN room_members m ON m.room_id = t.room_id AND m.user_id IN (t.owner_id, t.created_by);
