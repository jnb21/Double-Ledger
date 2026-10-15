package com.jordanbikong.ledger.journal;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** Rendered by Spring as a 404 problem+json response. */
public class JournalEntryNotFoundException extends ErrorResponseException {

    public JournalEntryNotFoundException(long id) {
        super(HttpStatus.NOT_FOUND,
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Journal entry " + id + " does not exist"),
                null);
    }
}
