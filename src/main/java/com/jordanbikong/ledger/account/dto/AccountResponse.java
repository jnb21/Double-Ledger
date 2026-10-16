package com.jordanbikong.ledger.account.dto;

import java.time.Instant;

import com.jordanbikong.ledger.account.Account;
import com.jordanbikong.ledger.account.AccountType;

/** JSON shape of an account. balance is in minor units (cents). */
public record AccountResponse(
        long id,
        String name,
        AccountType type,
        String currency,
        long balance,
        boolean allowNegative,
        Instant createdAt) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getName(),
                account.getType(),
                account.getCurrency(),
                account.getBalance(),
                account.isAllowNegative(),
                account.getCreatedAt());
    }
}
