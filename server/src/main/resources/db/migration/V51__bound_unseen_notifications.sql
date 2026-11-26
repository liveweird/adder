-- One recipient's notice-producing transactions can originate in different rooms, whose room locks do not serialize with each other.
-- This tiny guard row is therefore the global lock for the recipient's unseen-notice bound. Producers create missing rows lazily and
-- lock every event's recipients in ascending id order before any actor budget or notice mutation.
--
-- Delete class: HARD. A guard is coordination state, not a record; it follows the account. Existing recipients are marked for the
-- bounded background normalization because an upgraded database may already hold more than the new 5,000-unseen limit. New guard rows
-- default to normalized.
CREATE TABLE notification_recipient_guards (
    recipient_id       BIGINT  PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    needs_normalization BOOLEAN NOT NULL DEFAULT FALSE
);

INSERT INTO notification_recipient_guards (recipient_id, needs_normalization)
SELECT recipient_id, TRUE
FROM notifications
WHERE seen_at IS NULL
GROUP BY recipient_id
HAVING count(*) > 5000;

CREATE INDEX idx_notification_recipient_guards_normalization
    ON notification_recipient_guards (recipient_id)
    WHERE needs_normalization;

-- The bound includes mail-only rows, so V27's bell index (`... WHERE seen_at IS NULL AND in_app`) is too narrow. This index serves the
-- set-oriented overflow probe and the oldest-available trim without walking a recipient's retained seen history.
CREATE INDEX idx_notifications_unseen_recipient
    ON notifications (recipient_id, id)
    WHERE seen_at IS NULL;
