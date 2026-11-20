-- The emoji reactions of the LIVE messages (0.6.0): one row per (message, emoji, person). The table holds the CURRENT state of a toggle,
-- nothing else; the audit trail holds the changes (`message.reaction_changed`, ids only, never the emoji).
--
-- Delete class (persistence.md): HARD delete. A reaction is a join toggled on and off, nothing references a row, and a re-add must not
-- collide with a dead one. A deleted message's reactions are cleared in the delete's own transaction (a message follower), and the rows
-- also go with their message or user (ON DELETE CASCADE: a test purge; users are only ever soft-deleted).
--
-- `emoji` is stored exactly as sent and compared by BYTES (`COLLATE "C"`, whatever the database's locale): a heart with and without its
-- variation selector are two values, and a family of four joined by zero-width joiners is one value of 7 characters and 25 bytes. The
-- 32 characters hold the longest accepted value (the application refuses more than 10 code points and 32 UTF-16 units). The CHECK is
-- the floor only: what counts as ONE emoji is a rule in Kotlin (`threads/Reactions.kt`), never a CHECK (the V5 rule).
-- The key order groups a message's rows by emoji, which is how they are read; there is deliberately no other index (a person's own
-- reactions of a message are found through the same key).
CREATE TABLE message_reactions (
    message_id INTEGER     NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    user_id    BIGINT      NOT NULL REFERENCES users(id)    ON DELETE CASCADE,
    emoji      VARCHAR(32) COLLATE "C" NOT NULL CHECK (char_length(emoji) >= 1),
    created_at BIGINT      NOT NULL,
    PRIMARY KEY (message_id, emoji, user_id)
);
