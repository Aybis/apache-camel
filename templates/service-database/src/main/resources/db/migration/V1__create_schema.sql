-- Schema of __SERVICE__, applied by Flyway at start-up. Never edit a migration once it has run anywhere;
-- add V2__..., V3__... instead. Change columns in two releases: expand (add), then contract (drop).

-- Idempotency: a message is processed once per id. Insert before acting; a duplicate violates the key.
CREATE TABLE processed_message (
    message_id   TEXT        PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
