package com.jordanbikong.ledger.journal.dto;

import com.jordanbikong.ledger.journal.Direction;
import com.jordanbikong.ledger.journal.Posting;

public record PostingResponse(long id, long accountId, Direction direction, long amount) {

    public static PostingResponse from(Posting posting) {
        return new PostingResponse(posting.getId(), posting.getAccountId(), posting.getDirection(), posting.getAmount());
    }
}
