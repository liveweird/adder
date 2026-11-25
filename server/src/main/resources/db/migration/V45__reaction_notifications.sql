-- A reaction notice is one live pointer per (message author, reacting person), regardless of how many emoji that person leaves.
-- Ordinary message notices keep the V27 rule of one row per (message, recipient). The split partial indexes let several people react
-- to one author's message without weakening the uniqueness of mentions, quotes and reply notices.
DROP INDEX uq_notifications_message_recipient;
CREATE UNIQUE INDEX uq_notifications_message_recipient ON notifications (message_id, recipient_id)
    WHERE message_id IS NOT NULL AND notification_type <> 'REACTION_ADDED';
CREATE UNIQUE INDEX uq_notifications_reaction_actor ON notifications (message_id, recipient_id, actor_id)
    WHERE message_id IS NOT NULL AND notification_type = 'REACTION_ADDED';
