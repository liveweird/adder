-- A reply starts watching its thread, but a person's explicit Unwatch remains sticky across later replies and hand-overs.
-- Existing rows are active watches. An inactive row still belongs to the membership foreign key, so leaving forgets it.
ALTER TABLE thread_watches
    ADD COLUMN watching BOOLEAN NOT NULL DEFAULT TRUE;
