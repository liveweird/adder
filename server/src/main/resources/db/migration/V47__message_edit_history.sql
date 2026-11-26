-- Saved bodies are restricted security history, retained only while their live message and thread remain.
CREATE TABLE message_revisions (
    id SERIAL PRIMARY KEY,
    message_id INTEGER NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    body VARCHAR(8000) NOT NULL CHECK (char_length(body) >= 1),
    created_at BIGINT NOT NULL CHECK (created_at >= 0),
    superseded_at BIGINT NOT NULL CHECK (superseded_at >= created_at)
);
CREATE INDEX idx_message_revisions_message ON message_revisions (message_id, id);
