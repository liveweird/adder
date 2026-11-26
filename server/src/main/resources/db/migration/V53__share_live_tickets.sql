-- Shared, single-use WebSocket handshake tickets. Only the SHA-256 digest of the 32-byte bearer is stored; the raw ticket and
-- reusable JWT never enter PostgreSQL. The binding is sufficient to re-judge the exact access token at redemption.
--
-- The singleton guard serializes the short capacity decision across application instances. Expired rows are cleaned in bounded
-- batches; the guarded active-row count enforces live.maxTickets cluster-wide without relying on stale-row cleanup completeness.
CREATE TABLE live_ticket_capacity_guard (
    singleton  BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton)
);
INSERT INTO live_ticket_capacity_guard (singleton) VALUES (TRUE);

CREATE TABLE live_tickets (
    ticket_hash           CHAR(64) PRIMARY KEY,
    user_id               BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    is_admin              BOOLEAN NOT NULL,
    roles                 TEXT[] NOT NULL,
    access_jti            VARCHAR(36) NOT NULL,
    credential_revision   BIGINT NOT NULL CHECK (credential_revision >= 0),
    token_expires_at      BIGINT NOT NULL,
    expires_at            BIGINT NOT NULL
);
CREATE INDEX idx_live_tickets_expiry ON live_tickets (expires_at, ticket_hash);
