-- Durable refresh-token replay grace. The raw JWT is never stored: jti is the token's random identifier already held by the denylist.
-- A per-user guard serializes refresh rotation with logout without making unrelated accounts share a global lock. Preallocated slots
-- keep the operational rotation state at the existing 10,000-record bound; an unrecorded rotation remains strict one-time use.
CREATE TABLE refresh_rotation_user_guards (
    user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE refresh_rotation_slots (
    slot INTEGER PRIMARY KEY CHECK (slot BETWEEN 1 AND 10000)
);
INSERT INTO refresh_rotation_slots (slot) SELECT generate_series(1, 10000);

CREATE TABLE refresh_rotations (
    jti              VARCHAR(36) PRIMARY KEY REFERENCES revoked_tokens(jti) ON DELETE CASCADE,
    slot             INTEGER NOT NULL UNIQUE REFERENCES refresh_rotation_slots(slot),
    user_id          BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    grace_expires_at BIGINT NOT NULL,
    token_expires_at BIGINT NOT NULL,
    grace_reuses     SMALLINT NOT NULL DEFAULT 0 CHECK (grace_reuses BETWEEN 0 AND 2),
    CHECK (grace_expires_at <= token_expires_at)
);

CREATE INDEX idx_refresh_rotations_user ON refresh_rotations (user_id);
CREATE INDEX idx_refresh_rotations_token_expiry ON refresh_rotations (token_expires_at, jti);
CREATE INDEX idx_refresh_rotations_grace_expiry ON refresh_rotations (grace_expires_at, jti);
