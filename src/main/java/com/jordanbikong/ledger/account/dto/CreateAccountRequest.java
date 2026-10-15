package com.jordanbikong.ledger.account.dto;

import com.jordanbikong.ledger.account.AccountType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body of POST /accounts. Validated before it reaches the service. */
public record CreateAccountRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull AccountType type,
        @NotNull @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter uppercase ISO 4217 code") String currency) {
}
