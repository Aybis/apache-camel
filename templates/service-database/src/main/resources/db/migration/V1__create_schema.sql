-- Schema of __SERVICE__, applied by Flyway at start-up. Never edit a migration once it has run anywhere;
-- add V2__..., V3__... instead. Change columns in two releases: expand (add), then contract (drop).

-- Idempotency: a message is processed once per id. Claim it BEFORE acting, with
--   INSERT INTO processed_message (message_id) VALUES (?) ON CONFLICT DO NOTHING
-- and act only when the update count is 1 (0 = duplicate, skip it). Do not rely on catching a
-- unique violation: in PostgreSQL it aborts the whole transaction.
-- ProcessedMessages.java does this; old rows are purged after processed-message.retention (default 30 days).
CREATE TABLE processed_message (
    message_id   TEXT        PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX processed_message_processed_at ON processed_message (processed_at);
