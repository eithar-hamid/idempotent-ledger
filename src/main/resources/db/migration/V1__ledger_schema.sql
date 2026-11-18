-- Amounts are stored in minor units (e.g. cents, fils) as BIGINT. Never floating point.

CREATE TABLE account (
    id              UUID         PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    currency        CHAR(3)      NOT NULL,
    balance_minor   BIGINT       NOT NULL DEFAULT 0,
    allow_negative  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_account_balance CHECK (allow_negative OR balance_minor >= 0)
);

-- One row per accepted request. The unique key is what makes retries safe:
-- the first statement of every transfer is an INSERT against this constraint.
CREATE TABLE journal_entry (
    id               UUID         PRIMARY KEY,
    idempotency_key  VARCHAR(100) NOT NULL,
    request_hash     CHAR(64)     NOT NULL,
    description      VARCHAR(255),
    created_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_journal_entry_idempotency_key UNIQUE (idempotency_key)
);

-- Signed amounts: positive increases the account balance, negative decreases it.
-- The lines of one journal entry always sum to zero.
CREATE TABLE ledger_line (
    id            BIGSERIAL PRIMARY KEY,
    entry_id      UUID      NOT NULL REFERENCES journal_entry (id),
    account_id    UUID      NOT NULL REFERENCES account (id),
    amount_minor  BIGINT    NOT NULL,
    currency      CHAR(3)   NOT NULL,
    CONSTRAINT ck_ledger_line_non_zero CHECK (amount_minor <> 0)
);

CREATE INDEX ix_ledger_line_account ON ledger_line (account_id, id);
CREATE INDEX ix_ledger_line_entry   ON ledger_line (entry_id);
