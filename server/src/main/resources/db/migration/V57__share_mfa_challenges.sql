-- A login may issue a code on one node and verify it on another. The opaque 128-bit challenge id is stored as a SHA-256 digest.
-- The six-digit code and its captured email identity are encrypted with the existing deployment field key before insertion.
CREATE TABLE mfa_challenge_capacity_guard (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton)
);
INSERT INTO mfa_challenge_capacity_guard (singleton) VALUES (TRUE);

CREATE TABLE mfa_challenges (
    issue_order          BIGSERIAL PRIMARY KEY,
    challenge_hash       VARCHAR(64) NOT NULL UNIQUE,
    user_id              BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    encrypted_email      TEXT NOT NULL CHECK (encrypted_email LIKE 'enc:v1:%'),
    encrypted_code       TEXT NOT NULL CHECK (encrypted_code LIKE 'enc:v1:%'),
    credential_revision  BIGINT NOT NULL CHECK (credential_revision >= 0),
    expires_at           BIGINT NOT NULL,
    attempts             INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0)
);
CREATE INDEX idx_mfa_challenges_expiry ON mfa_challenges (expires_at, issue_order);
CREATE INDEX idx_mfa_challenges_user ON mfa_challenges (user_id, issue_order);
