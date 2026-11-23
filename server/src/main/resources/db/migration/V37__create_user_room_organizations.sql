-- Each person owns one private room-organization document. Room ids in the JSON are deliberately weak references: membership and
-- active-room joins filter them on every read, so leaving or deleting a room cannot disclose it and needs no cross-aggregate lock.
CREATE TABLE user_room_organizations (
    user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    document JSONB NOT NULL DEFAULT '{"groups":[],"rooms":[]}'::jsonb
);
