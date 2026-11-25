-- Room membership notices have no thread or message. The existing membership FK removes them on leave or removal.
ALTER TABLE notifications ALTER COLUMN thread_id DROP NOT NULL;
ALTER TABLE notifications ADD CONSTRAINT ck_notifications_target CHECK (
    (notification_type = 'ROOM_ADDED' AND thread_id IS NULL AND message_id IS NULL)
    OR (notification_type <> 'ROOM_ADDED' AND thread_id IS NOT NULL)
);
CREATE UNIQUE INDEX uq_notifications_room_membership ON notifications (room_id, recipient_id)
    WHERE notification_type = 'ROOM_ADDED';
