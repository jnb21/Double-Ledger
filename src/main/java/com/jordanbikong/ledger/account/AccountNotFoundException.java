package com.jordanbikong.ledger.account;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** Rendered by Spring as a 404 problem+json response. */
public class AccountNotFoundException extends ErrorResponseException {

    public AccountNotFoundException(long id) {
        super(HttpStatus.NOT_FOUND,
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Account " + id + " does not exist"),
                null);
    }
}
