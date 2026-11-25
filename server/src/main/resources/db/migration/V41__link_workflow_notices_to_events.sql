ALTER TABLE notifications ADD COLUMN event_id INT REFERENCES thread_events(id) ON DELETE SET NULL;

CREATE INDEX idx_notifications_event ON notifications (event_id) WHERE event_id IS NOT NULL;
