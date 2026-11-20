-- The pins of a room (0.6.0): a thread or a single message that the room's members want kept in view. A pin is a POINTER, not a record.
--
-- Delete class (persistence.md): HARD delete. Unpinning removes the row, a deleted message loses its pin in the delete's own transaction
-- (the message follower of `threads/PinService.kt`), and the rows also go with their thread, message or room (ON DELETE CASCADE, a test
-- purge). Nothing references a pin, and the audit line `room.pin_changed` is the record of who changed what.
--
-- `message_id` NULL is a pin of the whole thread. A message pin carries its thread too, for the panel's link; a thread and one of its
-- messages can both be pinned, so the two kinds have their own partial unique index. `pinned_by` references `users` with no cascade (a
-- user is soft-deleted; the pin keeps saying who made it). The cap of 50 pins a room is enforced in code under the room lock.
CREATE TABLE room_pins (
    id         SERIAL  PRIMARY KEY,
    room_id    INTEGER NOT NULL REFERENCES rooms(id)    ON DELETE CASCADE,
    thread_id  INTEGER NOT NULL REFERENCES threads(id)  ON DELETE CASCADE,
    message_id INTEGER          REFERENCES messages(id) ON DELETE CASCADE,
    pinned_by  BIGINT  NOT NULL REFERENCES users(id),
    pinned_at  BIGINT  NOT NULL
);
CREATE UNIQUE INDEX uq_room_pins_thread  ON room_pins (thread_id)  WHERE message_id IS NULL;
CREATE UNIQUE INDEX uq_room_pins_message ON room_pins (message_id) WHERE message_id IS NOT NULL;
CREATE INDEX idx_room_pins_room ON room_pins (room_id, pinned_at);
