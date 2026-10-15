-- V1: core ledger schema.
-- The database is the last line of defense: every rule here holds even if
-- the application code has a bug. Money is stored in minor units (cents) as
-- BIGINT, never as floating point.

-- ---------------------------------------------------------------------------
-- Accounts
-- ---------------------------------------------------------------------------
CREATE TABLE accounts (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        TEXT        NOT NULL UNIQUE CHECK (length(name) BETWEEN 1 AND 100),
    type        TEXT        NOT NULL CHECK (type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    currency    CHAR(3)     NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    -- Cached balance in minor units, on the account's normal side
    -- (debits - credits for ASSET/EXPENSE, credits - debits otherwise).
    -- Updated by the posting service in the same transaction as the postings.
    balance     BIGINT      NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Journal entries: one business event (a transfer, a payment, a reversal)
-- ---------------------------------------------------------------------------
CREATE TABLE journal_entries (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    description        TEXT        NOT NULL CHECK (length(description) BETWEEN 1 AND 500),
    -- Corrections are new entries that cancel an old one; UNIQUE means an
    -- entry can be reversed at most once.
    reverses_entry_id  BIGINT      UNIQUE REFERENCES journal_entries (id),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (reverses_entry_id <> id)
);

-- ---------------------------------------------------------------------------
-- Postings: the debit and credit lines of an entry
-- ---------------------------------------------------------------------------
CREATE TABLE postings (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entry_id    BIGINT NOT NULL REFERENCES journal_entries (id),
    account_id  BIGINT NOT NULL REFERENCES accounts (id),
    direction   TEXT   NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount      BIGINT NOT NULL CHECK (amount > 0)
);

CREATE INDEX postings_entry_id_idx   ON postings (entry_id);
CREATE INDEX postings_account_id_idx ON postings (account_id);

-- ---------------------------------------------------------------------------
-- Idempotency records: a retried request with the same key gets the stored
-- response instead of posting twice.
-- ---------------------------------------------------------------------------
CREATE TABLE idempotency_records (
    key              TEXT        PRIMARY KEY CHECK (length(key) BETWEEN 1 AND 255),
    -- SHA-256 of the request body, to detect a key reused with a different body.
    request_hash     TEXT        NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    -- NULL while the first request is still in progress.
    response_status  INT,
    response_body    JSONB,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Rule: every entry has at least two postings, debits equal credits, and all
-- of its accounts share one currency.
-- ---------------------------------------------------------------------------
CREATE FUNCTION assert_entry_balanced(p_entry_id BIGINT) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
    posting_count  INT;
    total_debits   NUMERIC;
    total_credits  NUMERIC;
    currency_count INT;
BEGIN
    SELECT count(*),
           coalesce(sum(p.amount) FILTER (WHERE p.direction = 'DEBIT'), 0),
           coalesce(sum(p.amount) FILTER (WHERE p.direction = 'CREDIT'), 0),
           count(DISTINCT a.currency)
      INTO posting_count, total_debits, total_credits, currency_count
      FROM postings p
      JOIN accounts a ON a.id = p.account_id
     WHERE p.entry_id = p_entry_id;

    IF posting_count < 2 THEN
        RAISE EXCEPTION 'journal entry % has % posting(s); at least 2 required', p_entry_id, posting_count
            USING ERRCODE = 'check_violation';
    END IF;

    IF total_debits <> total_credits THEN
        RAISE EXCEPTION 'journal entry % is unbalanced: debits % <> credits %', p_entry_id, total_debits, total_credits
            USING ERRCODE = 'check_violation';
    END IF;

    IF currency_count > 1 THEN
        RAISE EXCEPTION 'journal entry % mixes % currencies', p_entry_id, currency_count
            USING ERRCODE = 'check_violation';
    END IF;
END;
$$;

CREATE FUNCTION check_entry_on_entry_insert() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM assert_entry_balanced(NEW.id);
    RETURN NULL;
END;
$$;

CREATE FUNCTION check_entry_on_posting_insert() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM assert_entry_balanced(NEW.entry_id);
    RETURN NULL;
END;
$$;

-- DEFERRABLE INITIALLY DEFERRED: the check runs at COMMIT, after all of the
-- entry's postings are inserted, not after each row.
CREATE CONSTRAINT TRIGGER journal_entries_balanced
    AFTER INSERT ON journal_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_entry_on_entry_insert();

-- Also catches postings added to an existing entry in a later transaction.
CREATE CONSTRAINT TRIGGER postings_balanced
    AFTER INSERT ON postings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_entry_on_posting_insert();

-- ---------------------------------------------------------------------------
-- Rule: history is append-only. Mistakes are fixed with reversing entries.
-- ---------------------------------------------------------------------------
CREATE FUNCTION reject_history_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER journal_entries_append_only
    BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION reject_history_change();

CREATE TRIGGER journal_entries_no_truncate
    BEFORE TRUNCATE ON journal_entries
    FOR EACH STATEMENT EXECUTE FUNCTION reject_history_change();

CREATE TRIGGER postings_append_only
    BEFORE UPDATE OR DELETE ON postings
    FOR EACH ROW EXECUTE FUNCTION reject_history_change();

CREATE TRIGGER postings_no_truncate
    BEFORE TRUNCATE ON postings
    FOR EACH STATEMENT EXECUTE FUNCTION reject_history_change();
