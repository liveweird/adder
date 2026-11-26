-- Shared live-session coordination for multi-replica deployments. A short guard-row lock serializes the 16-session user cap across
-- instances; leases and foreground state are operational, renewable records rather than user history. Session ids are random UUIDs,
-- globally unique and safe to expose in process diagnostics; no access token, ticket or token id is stored.
--
-- Delete class: HARD. Release and bounded expiry cleanup remove leases. The guard follows a hard-deleted account; production accounts
-- are soft-deleted, while the access-token verdict closes and releases their sessions.
CREATE TABLE live_session_user_guards (
    user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE live_session_leases (
    session_id            UUID   PRIMARY KEY,
    instance_id           UUID   NOT NULL,
    user_id               BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_expires_at      BIGINT NOT NULL,
    socket_expires_at     BIGINT NOT NULL,
    foreground_expires_at BIGINT,
    CHECK (socket_expires_at <= token_expires_at)
);

-- Admission counts one user's fresh sockets; cleanup takes expired leases in deadline/id order.
CREATE INDEX idx_live_session_leases_user_socket
    ON live_session_leases (user_id, socket_expires_at, session_id);
CREATE INDEX idx_live_session_leases_socket_expiry
    ON live_session_leases (socket_expires_at, session_id);

-- Presence is a candidate-user lookup, and the partial index excludes tabs that never announced foreground state or cleared it.
CREATE INDEX idx_live_session_leases_foreground
    ON live_session_leases (user_id, foreground_expires_at)
    WHERE foreground_expires_at IS NOT NULL;
