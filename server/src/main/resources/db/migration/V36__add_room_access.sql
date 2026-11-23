-- A room chooses its access policy once, at creation. Existing rooms stay undiscoverable to
-- non-members. Join requests are an owner-reviewed record; a direct invitation or approval
-- removes the record, while a rejection stays so the requester can see the decision.
ALTER TABLE rooms
    ADD COLUMN access_type VARCHAR(24) NOT NULL DEFAULT 'RESTRICTED_HIDDEN';

CREATE INDEX idx_rooms_access_type ON rooms (access_type) WHERE NOT marked_as_deleted;

CREATE TABLE room_join_requests (
    id SERIAL PRIMARY KEY,
    room_id INTEGER NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status VARCHAR(10) NOT NULL,
    requested_at BIGINT NOT NULL,
    UNIQUE (room_id, user_id)
);
CREATE INDEX idx_room_join_requests_pending
    ON room_join_requests (room_id, requested_at, user_id)
    WHERE status = 'PENDING';
CREATE INDEX idx_room_join_requests_pending_id
    ON room_join_requests (room_id, id)
    WHERE status = 'PENDING';
