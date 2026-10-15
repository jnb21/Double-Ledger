package com.jordanbikong.ledger.account;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** Rendered by Spring as a 409 problem+json response. */
public class DuplicateAccountNameException extends ErrorResponseException {

    public DuplicateAccountNameException(String name, Throwable cause) {
        super(HttpStatus.CONFLICT,
                ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "An account named '" + name + "' already exists"),
                cause);
    }
}
