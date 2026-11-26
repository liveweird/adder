-- Cross-instance credential-guess state. Identity keys are HMAC-SHA256 digests under a deployment-secret-derived key; submitted
-- addresses are never stored. The state row may outlive its ordinary failure window while MFA escalation or code pacing remains.
CREATE TABLE account_login_attempts (
    identity_hash          CHAR(64) PRIMARY KEY,
    failures               INTEGER NOT NULL DEFAULT 0 CHECK (failures >= 0),
    locked_until           BIGINT NOT NULL DEFAULT 0,
    state_expires_at       BIGINT NOT NULL DEFAULT 0,
    code_failed            BOOLEAN NOT NULL DEFAULT FALSE,
    escalation_level       INTEGER NOT NULL DEFAULT 0 CHECK (escalation_level BETWEEN 0 AND 32),
    escalation_anchor      BIGINT NOT NULL DEFAULT 0,
    escalation_expires_at  BIGINT NOT NULL DEFAULT 0,
    code_guesses           INTEGER NOT NULL DEFAULT 0 CHECK (code_guesses >= 0),
    code_expires_at        BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_account_login_attempts_state_expiry
    ON account_login_attempts (state_expires_at, identity_hash);
CREATE INDEX idx_account_login_attempts_all_expiry
    ON account_login_attempts (GREATEST(state_expires_at, escalation_expires_at, code_expires_at), identity_hash);

-- Only an unknown identity entering the bounded ordinary-failure set takes this row lock. Known accounts remain admissible when
-- that set is full, matching the existing account-enumeration posture.
CREATE TABLE account_login_attempt_capacity_guard (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton)
);
INSERT INTO account_login_attempt_capacity_guard (singleton) VALUES (TRUE);

-- Password-reset requests use a separate digest domain and a separate cap. This low-rate guard makes prune/count/claim one atomic
-- capacity decision across every app instance.
CREATE TABLE password_reset_cooldowns (
    identity_hash CHAR(64) PRIMARY KEY,
    expires_at    BIGINT NOT NULL
);
CREATE INDEX idx_password_reset_cooldowns_expiry
    ON password_reset_cooldowns (expires_at, identity_hash);
CREATE TABLE password_reset_cooldown_capacity_guard (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton)
);
INSERT INTO password_reset_cooldown_capacity_guard (singleton) VALUES (TRUE);
