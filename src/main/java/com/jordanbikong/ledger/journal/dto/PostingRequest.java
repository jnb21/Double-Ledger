package com.jordanbikong.ledger.journal.dto;

import com.jordanbikong.ledger.journal.Direction;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** One line of a new journal entry. amount is in minor units (cents). */
public record PostingRequest(
        @NotNull Long accountId,
        @NotNull Direction direction,
        @NotNull @Positive Long amount) {
}
