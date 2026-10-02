-- Console schema. Owned by the console; change only by adding V<n>__*.sql files.

CREATE TABLE service (
    name         VARCHAR(64)  PRIMARY KEY,
    domain       VARCHAR(64)  NOT NULL DEFAULT 'unassigned',
    description  TEXT         NOT NULL DEFAULT '',
    port         INTEGER,
    local_only   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Current settings per service; version increases by one on every change.
CREATE TABLE service_config (
    service      VARCHAR(64)  PRIMARY KEY REFERENCES service (name) ON DELETE CASCADE,
    version      BIGINT       NOT NULL DEFAULT 0,
    log_levels   JSONB        NOT NULL DEFAULT '{}'::jsonb,
    properties   JSONB        NOT NULL DEFAULT '{}'::jsonb,
    updated_at   TIMESTAMPTZ,
    updated_by   VARCHAR(128)
);

-- Append-only history of every change.
CREATE TABLE config_audit (
    id            BIGSERIAL    PRIMARY KEY,
    at            TIMESTAMPTZ  NOT NULL,
    service       VARCHAR(64)  NOT NULL REFERENCES service (name) ON DELETE CASCADE,
    changed_by    VARCHAR(128) NOT NULL,
    comment       TEXT,
    from_version  BIGINT       NOT NULL,
    to_version    BIGINT       NOT NULL,
    log_levels    JSONB        NOT NULL,
    properties    JSONB        NOT NULL,
    UNIQUE (service, to_version)
);
CREATE INDEX config_audit_service_at ON config_audit (service, at DESC);

-- Latest heartbeat per running instance. Shared by all console replicas.
CREATE TABLE heartbeat (
    service      VARCHAR(64)  NOT NULL REFERENCES service (name) ON DELETE CASCADE,
    instance     VARCHAR(255) NOT NULL,
    payload      JSONB        NOT NULL,
    received_at  TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (service, instance)
);
