-- One recipient-wide SMTP attempt interval across every app instance. The fixed slot pool preserves the former in-process
-- 50,000-recipient fail-closed bound without serialising reservations for different recipients on one guard row.
CREATE TABLE notification_digest_recipient_slots (
    slot  INTEGER PRIMARY KEY CHECK (slot BETWEEN 1 AND 50000)
);

INSERT INTO notification_digest_recipient_slots (slot)
SELECT generate_series(1, 50000);

CREATE TABLE notification_digest_recipient_pacing (
    recipient_id  BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    slot          INTEGER NOT NULL UNIQUE REFERENCES notification_digest_recipient_slots(slot),
    attempted_at  BIGINT NOT NULL
);

CREATE INDEX idx_notification_digest_recipient_pacing_attempted
    ON notification_digest_recipient_pacing (attempted_at, recipient_id);
