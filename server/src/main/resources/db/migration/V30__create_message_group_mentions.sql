-- The GROUP mentions of the LIVE message bodies (1.1.0), a DERIVED index beside `message_mentions` (V17): one row per (message, team), plus
-- one for `<@watchers>` and one for `<@everyone>` when the body holds them. The message's own write (post, edit, delete) keeps it in step
-- with the body, in the same transaction, under the room lock. It is what a message's `groupMentions` reads and what the thread list's
-- `mentionsMe` filter starts from; WHO a group mention notifies is decided at write time from the live roster, watchers and members
-- (never from this table), so a row records only what the body says.
--
-- Delete class (persistence.md): HARD delete, the `message_mentions` reasoning. Nothing references a row, it is rebuilt from the body by the
-- message's own write, and a deleted message's group mentions must not outlive its scrubbed body (a moderator's delete clears them in the
-- delete's own transaction; the boot purge `StaleDerivedRows` is the net for a rolled-back binary). The rows also go with their message,
-- thread, room or team (ON DELETE CASCADE; teams are only soft-deleted, so a team that is deleted or loses its handle keeps its rows,
-- inert: every reader joins ACTIVE teams that have a handle, and such a token renders as plain text).
--
-- `group_kind` is `TEAM`, `WATCHERS` or `EVERYONE` (the Kotlin enum `MentionGroupKind` is the whitelist, no CHECK). `team_id` is set for
-- TEAM and null otherwise; NULLS NOT DISTINCT makes the keyword rows unique per message too (one `@everyone` row however often it is
-- written). `thread_id` and `room_id` are copied from the message's thread (a message never changes thread, a thread never changes
-- room) so `mentionsMe` starts at ONE room: `idx_message_group_mentions_room (room_id, thread_id, group_kind, team_id)` serves its
-- `EXISTS` probes (ONE thread of one room, then a keyword or the caller's teams). There is deliberately no index on `message_id` beyond the unique key's: the
-- message's own write reads and clears its rows through it.
-- No backfill and no version column: no message written before this migration has a row, so an old literal `<@everyone>` stays plain text.
CREATE TABLE message_group_mentions (
    message_id INTEGER     NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    group_kind VARCHAR(10) NOT NULL,
    team_id    INTEGER              REFERENCES teams(id)    ON DELETE CASCADE,
    thread_id  INTEGER     NOT NULL REFERENCES threads(id)  ON DELETE CASCADE,
    room_id    INTEGER     NOT NULL REFERENCES rooms(id)    ON DELETE CASCADE,
    CONSTRAINT uq_message_group_mentions UNIQUE NULLS NOT DISTINCT (message_id, group_kind, team_id)
);
CREATE INDEX idx_message_group_mentions_room ON message_group_mentions (room_id, thread_id, group_kind, team_id);
