ALTER TABLE threads
    ADD COLUMN marked_as_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN merged_into_thread_number INTEGER,
    ADD CONSTRAINT fk_threads_merged_into
        FOREIGN KEY (room_id, merged_into_thread_number) REFERENCES threads (room_id, number),
    ADD CONSTRAINT ck_threads_merge_not_self CHECK (merged_into_thread_number <> number);

CREATE INDEX idx_threads_marked_as_deleted ON threads (marked_as_deleted);
CREATE INDEX idx_threads_merge_target ON threads (room_id, merged_into_thread_number)
    WHERE merged_into_thread_number IS NOT NULL AND NOT marked_as_deleted;
