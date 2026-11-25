CREATE TABLE browser_push_subscriptions (
    id SERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    endpoint_hash BYTEA NOT NULL UNIQUE,
    endpoint_encrypted TEXT NOT NULL,
    credential_revision BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    CHECK (credential_revision >= 0)
);

CREATE INDEX idx_browser_push_subscriptions_user ON browser_push_subscriptions (user_id, id);

CREATE TABLE notification_push_queue (
    notification_id INTEGER NOT NULL REFERENCES notifications(id) ON DELETE CASCADE,
    subscription_id INTEGER NOT NULL REFERENCES browser_push_subscriptions(id) ON DELETE CASCADE,
    PRIMARY KEY (notification_id, subscription_id)
);

CREATE INDEX idx_notification_push_queue_subscription ON notification_push_queue (subscription_id, notification_id);
