package com.jordanbikong.ledger.journal;

import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long> {

    /** Loads the postings in the same query, so they can be read after the transaction ends. */
    @EntityGraph(attributePaths = "postings")
    Optional<JournalEntry> findWithPostingsById(long id);
}
