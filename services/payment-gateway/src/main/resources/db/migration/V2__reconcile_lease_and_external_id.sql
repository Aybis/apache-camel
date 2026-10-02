-- Expand only: compatible with the previous release, which neither reads nor writes these.
-- Flyway runs as the service role (statement_timeout 5s); building the index on a large table may take longer.
SET LOCAL statement_timeout = '5min';

-- Lease for the reconciliation claim: a replica that claims a transfer for a status check sets this, and the
-- others skip the transfer until it passes, so the bank gets one inquiry per transfer per round.
ALTER TABLE transfer ADD COLUMN next_check_at TIMESTAMPTZ;

-- The X-EXTERNAL-ID sent to the bank identifies one request per bank (SNAP requires it unique per day);
-- the database makes sure two transfers can never carry the same one.
CREATE UNIQUE INDEX transfer_external_id_uq ON transfer (bank, external_id);
