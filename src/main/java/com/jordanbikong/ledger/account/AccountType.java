package com.jordanbikong.ledger.account;

/**
 * The five account types of double-entry bookkeeping. The type decides which
 * side increases the balance (its "normal side").
 */
public enum AccountType {
    ASSET,
    LIABILITY,
    EQUITY,
    REVENUE,
    EXPENSE;

    /** ASSET and EXPENSE grow with debits; the others grow with credits. */
    public boolean isDebitNormal() {
        return this == ASSET || this == EXPENSE;
    }
}
