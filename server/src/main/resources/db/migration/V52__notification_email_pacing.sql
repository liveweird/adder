-- Digest SMTP pacing: one attempt per (actor, recipient) per hour across every room. The row is reserved only after the digest's
-- send-time access/preference recheck and before SMTP; a failed or outcome-unknown attempt deliberately keeps the reservation.
-- Claims deferred by the reservation race are released and remain pending. This table does not affect the bell, browser push or
-- account-security email paths.
--
-- Delete class: HARD delete. Expired rows are removed in bounded sweep batches through the attempted_at index; either account's
-- hard deletion cascades its pairs (production accounts are soft-deleted, but test cleanup and future erasure remain complete).
CREATE TABLE notification_email_pacing (
    actor_id      BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    recipient_id  BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    attempted_at  BIGINT NOT NULL,
    PRIMARY KEY (actor_id, recipient_id)
);
CREATE INDEX idx_notification_email_pacing_attempted ON notification_email_pacing (attempted_at, actor_id, recipient_id);
