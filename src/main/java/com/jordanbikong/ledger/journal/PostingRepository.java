package com.jordanbikong.ledger.journal;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.jordanbikong.ledger.journal.dto.AccountPostingResponse;

public interface PostingRepository extends JpaRepository<Posting, Long> {

    /** An account's history, newest first, with each entry's description and time. */
    @Query("""
            SELECT new com.jordanbikong.ledger.journal.dto.AccountPostingResponse(
                       p.id, e.id, e.description, p.direction, p.amount, e.createdAt)
              FROM Posting p JOIN p.entry e
             WHERE p.accountId = :accountId
             ORDER BY p.id DESC
            """)
    List<AccountPostingResponse> findHistory(long accountId);
}
