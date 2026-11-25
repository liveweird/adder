CREATE TABLE thread_mutes (
    thread_id INTEGER NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL,
    room_id INTEGER NOT NULL,
    PRIMARY KEY (thread_id, user_id),
    CONSTRAINT fk_thread_mutes_member FOREIGN KEY (room_id, user_id)
        REFERENCES room_members (room_id, user_id) ON DELETE CASCADE
);

CREATE INDEX idx_thread_mutes_member ON thread_mutes (user_id, room_id);
