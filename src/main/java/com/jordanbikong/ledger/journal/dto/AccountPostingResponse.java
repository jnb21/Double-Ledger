package com.jordanbikong.ledger.journal.dto;

import java.time.Instant;

import com.jordanbikong.ledger.journal.Direction;

/** One line of an account's history (its "statement"). */
public record AccountPostingResponse(
        long postingId,
        long entryId,
        String description,
        Direction direction,
        long amount,
        Instant createdAt) {
}
