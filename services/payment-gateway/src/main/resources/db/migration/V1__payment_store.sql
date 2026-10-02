-- Payment gateway store. The money-safety rules are enforced here, not only in Java:
--  * transfer.id (the caller's clientReferenceId) is the primary key: a reference can be sent at most once,
--    across every replica.
--  * a virtual-account number is unique per bank.
--  * a bank payment id credits at most one virtual account, at most once.
--  * amounts are NUMERIC(19,2), never floating point; statuses are constrained to the known values.

CREATE TABLE transfer (
    id                    VARCHAR(64)    PRIMARY KEY,
    bank                  VARCHAR(20)    NOT NULL,
    request               JSONB          NOT NULL,
    amount                NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency              CHAR(3)        NOT NULL,
    type                  VARCHAR(10)    NOT NULL CHECK (type IN ('INTRABANK', 'INTERBANK')),
    status                VARCHAR(10)    NOT NULL CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED', 'UNKNOWN')),
    external_id           VARCHAR(36)    NOT NULL,
    bank_reference        VARCHAR(64),
    bank_response_code    VARCHAR(16),
    bank_response_message VARCHAR(500),
    created_at            TIMESTAMPTZ    NOT NULL,
    updated_at            TIMESTAMPTZ    NOT NULL,
    status_checks         INTEGER        NOT NULL DEFAULT 0
);

-- Reconciliation reads only unresolved transfers.
CREATE INDEX transfer_open_idx ON transfer (updated_at) WHERE status IN ('PENDING', 'UNKNOWN');

CREATE TABLE virtual_account (
    id                    VARCHAR(64)    PRIMARY KEY,
    bank                  VARCHAR(20)    NOT NULL,
    request               JSONB          NOT NULL,
    virtual_account_no    VARCHAR(40),
    status                VARCHAR(10)    NOT NULL CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED', 'UNKNOWN')),
    bank_response_code    VARCHAR(16),
    bank_response_message VARCHAR(500),
    paid_amount           NUMERIC(19, 2),
    payment_request_id    VARCHAR(64),
    paid_at               TIMESTAMPTZ,
    created_at            TIMESTAMPTZ    NOT NULL,
    updated_at            TIMESTAMPTZ    NOT NULL,
    CHECK ((status = 'SUCCESS') = (payment_request_id IS NOT NULL AND paid_amount IS NOT NULL))
);

CREATE UNIQUE INDEX virtual_account_number_uq ON virtual_account (bank, virtual_account_no)
    WHERE virtual_account_no IS NOT NULL;
CREATE UNIQUE INDEX virtual_account_payment_uq ON virtual_account (bank, payment_request_id)
    WHERE payment_request_id IS NOT NULL;
