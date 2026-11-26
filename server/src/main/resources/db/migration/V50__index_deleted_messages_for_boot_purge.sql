-- Walk tombstones in id order during boot maintenance without reading the live message set.
CREATE INDEX idx_messages_deleted_id ON messages (id) WHERE marked_as_deleted;
