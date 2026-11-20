-- Email digest of notices (0.6.0): the sweep mails a notice that is still unseen after a delay, one digest per person (never the message text
-- or the thread title). `emailed_at` is the CLAIM: the sweep stamps it in a short transaction BEFORE it sends, so a notice is mailed at most
-- once (a failed send is audited and not retried) and two processes cannot send one notice twice (`FOR UPDATE SKIP LOCKED` on the claim).
-- It is null until claimed. A notice that was claimed and skipped (a kind the recipient switched off, a room they left) is never mailed later.
-- `idx_notifications_email_due` serves the claim: the oldest unseen, unclaimed notices, straight off the index.
--
-- `user_notification_preferences` (Lettuce's V84, kept to what Adder has): a row is a DISABLED (kind, channel) pair, no row means on, so a
-- new user and a new kind are on without a write (the V5 disabled-set model, replaced wholesale by one PUT). `notification_type` and
-- `channel` carry NO CHECK: the Kotlin enums are the whitelists and their names are persisted and frozen (the V5 rule); a stored name this
-- build does not know is ignored by every reader. `channel` has one legal value today, EMAIL (an in-app notice cannot be switched off).
--
-- Delete class (persistence.md): HARD delete. The table is a flag join with a wholesale-replace PUT (the `user_disabled_features` class); a
-- user's rows go with the user row (a test purge cascades; accounts are only soft-deleted, and a deleted account is never mailed).
ALTER TABLE notifications ADD COLUMN emailed_at BIGINT;
CREATE INDEX idx_notifications_email_due ON notifications (created_at) WHERE seen_at IS NULL AND emailed_at IS NULL;
CREATE TABLE user_notification_preferences (
    user_id           BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    notification_type VARCHAR(40) NOT NULL,
    channel           VARCHAR(10) NOT NULL,
    PRIMARY KEY (user_id, notification_type, channel)
);
