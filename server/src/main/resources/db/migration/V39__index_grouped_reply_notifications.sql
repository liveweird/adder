-- Grouped reply feeds read only the newest 5,000 unseen in-app THREAD_REPLIED rows of one recipient, in descending id order.
-- This partial index keeps each active-room candidate scan bounded without walking rows retained for a soft-deleted room.
CREATE INDEX idx_notifications_grouped_replies ON notifications (recipient_id, room_id, id)
 WHERE in_app AND seen_at IS NULL AND notification_type = 'THREAD_REPLIED';
