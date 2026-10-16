-- V2: accounts can't go below zero unless they are explicitly allowed to.
-- The posting service checks this first to return a clear 422; this CHECK
-- is the guarantee if that check ever has a bug.

ALTER TABLE accounts
    ADD COLUMN allow_negative BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE accounts
    ADD CONSTRAINT accounts_no_overdraft CHECK (allow_negative OR balance >= 0);
