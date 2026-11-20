-- The mentions of the LIVE message bodies (0.6.0), a DERIVED index: `message_mentions` holds one row per (message, mentioned member) and
-- nothing else. The message's own write (post, edit, delete) keeps it in step with the body, in the same transaction, under the room lock.
--
-- Delete class (persistence.md): HARD delete. Nothing references a row, it is rebuilt from the body by the message's own write, and a
-- deleted message's mentions must not outlive its scrubbed body (a moderator's delete clears them in the delete's own transaction). The
-- rows also go with their message, thread, room or user (ON DELETE CASCADE; users are only soft-deleted, a test purge hard-deletes them).
--
-- `thread_id` and `room_id` are copied from the message's thread (a message never changes thread, a thread never changes room) so the
-- thread list's `mentionedUserId` filter starts at ONE person in ONE room: `idx_message_mentions_user_room (user_id, room_id, thread_id)`
-- serves its `EXISTS` probe. There is deliberately no index on `message_id` beyond the primary key's: the message's own write reads and
-- clears its rows through it.
-- No backfill and no version column: no message written before this migration has a row, so an old literal `<@42>` stays plain text.
-- Mentions are parsed in Kotlin only (the grammar skips code and link regions the way the SPA's Markdown tree does).
CREATE TABLE message_mentions (
    message_id INTEGER NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    user_id    BIGINT  NOT NULL REFERENCES users(id)    ON DELETE CASCADE,
    thread_id  INTEGER NOT NULL REFERENCES threads(id)  ON DELETE CASCADE,
    room_id    INTEGER NOT NULL REFERENCES rooms(id)    ON DELETE CASCADE,
    PRIMARY KEY (message_id, user_id)
);
CREATE INDEX idx_message_mentions_user_room ON message_mentions (user_id, room_id, thread_id);
