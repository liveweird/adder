-- The hashtags of the LIVE message bodies (0.5.0), a DERIVED index: `message_tags` holds one row per (message, folded tag) and nothing
-- else, rebuilt from the bodies at any time by the versioned reindex at boot (`messages.tags_version`).
--
-- Delete class (persistence.md): HARD delete. Nothing references a row, it is rebuilt from the bodies, and a deleted message's tags
-- must not outlive its scrubbed body (a moderator's delete clears them in the same transaction); a soft-delete flag would keep
-- `#the-leaked-thing` findable. The rows also go with their message, thread or room (ON DELETE CASCADE, a test purge).
--
-- `tag` is the FOLDED key (the server lower-cases, `public.unaccent` strips the accents, `btrim` trims it, on write and on filter). It is 150 characters,
-- not 50, because folding can lengthen a tag (a ligature becomes three letters, a vulgar fraction up to five characters: `⅒` becomes " 1/10", so a 50-character tag can fold to 246) and the
-- writer truncates to this and trims the leading space `unaccent` puts before a fraction.
-- `thread_id` and `room_id` are copied from the message's thread (a message never changes thread, a thread never changes room) so both
-- reads start at ONE room: the thread list's tag filter and the room's tag list probe `idx_message_tags_room_tag` (room, tag, thread),
-- an index-only scan of that room's rows and nothing else. There is deliberately no index on `thread_id`: a thread is never deleted
-- (only a test purge cascades through one), so its foreign key needs no supporting index.
-- `tags_version` 0 means "not indexed yet": the constant default adds the column without rewriting the table, and the boot reindex
-- picks up every live message below the current grammar version. It carries NO CHECK on purpose (only code writes it, and a new CHECK
-- would scan the whole `messages` table under an exclusive lock). Hashtags are parsed in Kotlin only (the grammar needs `\p{L}` and
-- Markdown code/link skipping).
CREATE TABLE message_tags (
    message_id INTEGER NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    thread_id  INTEGER NOT NULL REFERENCES threads(id)  ON DELETE CASCADE,
    room_id    INTEGER NOT NULL REFERENCES rooms(id)    ON DELETE CASCADE,
    tag        VARCHAR(150) NOT NULL CHECK (char_length(tag) >= 1),
    PRIMARY KEY (message_id, tag)
);
CREATE INDEX idx_message_tags_room_tag ON message_tags (room_id, tag, thread_id);

ALTER TABLE messages ADD COLUMN tags_version SMALLINT NOT NULL DEFAULT 0;
