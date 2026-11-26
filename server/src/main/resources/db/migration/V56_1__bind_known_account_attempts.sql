-- A proved account owns one stable reservation even when an administrator changes its email. The digest remains the lookup key;
-- this nullable binding is populated only after a route has proved the durable user id. Anonymous/public-login rows stay unbound.
ALTER TABLE account_login_attempts
    ADD COLUMN user_id BIGINT NULL REFERENCES users(id) ON DELETE CASCADE;

CREATE UNIQUE INDEX uq_account_login_attempts_user
    ON account_login_attempts (user_id)
    WHERE user_id IS NOT NULL;
