package com.jordanbikong.ledger.journal.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of POST /journal-entries. Shape is checked here; money rules in PostingService. */
public record CreateJournalEntryRequest(
        @NotBlank @Size(max = 500) String description,
        @NotNull @Size(min = 2, max = 100) List<@NotNull @Valid PostingRequest> postings) {
}
