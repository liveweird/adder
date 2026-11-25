-- Stable recipient snapshots for `<@here>`. The snapshot is taken when the token is introduced and survives edits
-- that keep it; removing the token deletes it and adding it again takes a fresh snapshot.
CREATE TABLE message_here_mentions (
    message_id INTEGER NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    user_id    BIGINT  NOT NULL REFERENCES users(id)    ON DELETE CASCADE,
    thread_id  INTEGER NOT NULL REFERENCES threads(id)  ON DELETE CASCADE,
    room_id    INTEGER NOT NULL REFERENCES rooms(id)    ON DELETE CASCADE,
    PRIMARY KEY (message_id, user_id)
);
CREATE INDEX idx_message_here_mentions_user_room ON message_here_mentions (user_id, room_id, thread_id);
