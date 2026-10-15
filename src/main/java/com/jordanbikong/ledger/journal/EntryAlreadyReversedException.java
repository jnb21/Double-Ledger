package com.jordanbikong.ledger.journal;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** Rendered by Spring as a 409 problem+json response. */
public class EntryAlreadyReversedException extends ErrorResponseException {

    public EntryAlreadyReversedException(long id, Throwable cause) {
        super(HttpStatus.CONFLICT,
                ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Journal entry " + id + " has already been reversed"),
                cause);
    }
}
