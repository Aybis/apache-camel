-- Marks the one-time import of the pre-PostgreSQL file store (store.json, audit.jsonl) as done.
CREATE TABLE legacy_import (
    id          INT         PRIMARY KEY CHECK (id = 1),
    imported_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    source      TEXT        NOT NULL
);
