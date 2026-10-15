package com.jordanbikong.ledger.journal.dto;

import java.time.Instant;
import java.util.List;

import com.jordanbikong.ledger.journal.JournalEntry;

public record JournalEntryResponse(
        long id,
        String description,
        Long reversesEntryId,
        Instant createdAt,
        List<PostingResponse> postings) {

    public static JournalEntryResponse from(JournalEntry entry) {
        return new JournalEntryResponse(
                entry.getId(),
                entry.getDescription(),
                entry.getReversesEntryId(),
                entry.getCreatedAt(),
                entry.getPostings().stream().map(PostingResponse::from).toList());
    }
}
